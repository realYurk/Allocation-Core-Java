package io.allocation.kernel.exec;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.coord.Coord;
import io.allocation.kernel.coord.Utf8Order;
import io.allocation.kernel.money.Apportion;
import io.allocation.kernel.money.Overflow;
import io.allocation.kernel.plan.MemberFilter;
import io.allocation.kernel.plan.Plan;
import io.allocation.kernel.spec.AllocationRule;
import io.allocation.kernel.spec.AllocationSpec;
import io.allocation.kernel.spec.Grouping;
import io.allocation.kernel.spec.StageOperation;
import io.allocation.kernel.spec.StageSpec;
import io.allocation.kernel.spec.TargetMode;
import io.allocation.kernel.spec.TargetSpec;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Executes a validated {@link Plan}.
 *
 * <h2>Determinism</h2>
 * The engine must produce identical output for any worker count. That is achieved structurally, not by
 * luck: pools are the only parallel unit, each pool is computed entirely within one thread, and the
 * coordinator consumes results in ascending pool-key order. No accumulation depends on completion order,
 * so there is nothing to "usually work".
 *
 * <h2>Fail-closed</h2>
 * Any violation aborts the run. No partial result set is published, because a half-written ledger is
 * worse than no ledger: it looks like an answer.
 *
 * <h2>Memory</h2>
 * Rows do not accumulate in lists. Every stage buffer is a {@link RowStore}: it holds rows in memory up
 * to {@link Options#maxRowsInMemory()} and spills the rest to a temp file, preserving insertion order, so
 * {@code forEach} may be replayed any number of times. Peak heap therefore tracks the budget the caller
 * chose plus the number of pools, not the size of the input.
 *
 * <p>Two things are still held in memory by design, and both are worth naming rather than discovering:
 * the driver table for a rule (allocation needs random access to weights, not a stream), and the
 * coordinate keys of a stage that coalesces its output (coalescing <em>is</em> a map; the alternative is
 * an external sort, which is not worth its complexity while the map is the smaller side). The store
 * removes the part that had no such excuse: whole input and output row sets.
 */
public final class Executor {

    /**
     * Execution options.
     *
     * @param workers              pool-level parallelism; {@code 0} or {@code 1} runs sequentially
     * @param blockOnMissingTarget treat "declared target has no driver weight" as fatal instead of a warning
     * @param maxRowsInMemory      rows a stage buffer may hold before it spills to disk;
     *                             {@link #UNBOUNDED_ROWS_IN_MEMORY} never spills
     */
    public record Options(int workers, boolean blockOnMissingTarget, long maxRowsInMemory) {

        /**
         * Rows a stage buffer keeps in memory by default.
         *
         * <p>Chosen for the case that actually happens: a few hundred thousand rows fit without
         * touching the disk, and a container with a 512MB heap is not at risk. Callers who know their
         * input is small can raise it; callers in a 64MB container should lower it.
         */
        public static final long DEFAULT_MAX_ROWS_IN_MEMORY = 250_000L;

        /** Never spill. Appropriate for a bounded input and a heap you control. */
        public static final long UNBOUNDED_ROWS_IN_MEMORY = Long.MAX_VALUE;

        public Options {
            if (maxRowsInMemory < 1) {
                throw new IllegalArgumentException("maxRowsInMemory must be at least 1, got " + maxRowsInMemory);
            }
        }

        /** Options with the default spill budget. */
        public Options(int workers, boolean blockOnMissingTarget) {
            this(workers, blockOnMissingTarget, DEFAULT_MAX_ROWS_IN_MEMORY);
        }

        public static Options sequential() {
            return new Options(1, false);
        }

        public static Options parallel(int workers) {
            return new Options(workers, false);
        }

        /** Same options with a different spill budget. */
        public Options withMaxRowsInMemory(long rows) {
            return new Options(workers, blockOnMissingTarget, rows);
        }
    }

    private static final DateTimeFormatter RFC3339 =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final Plan plan;
    private final AllocationInput input;
    private final RunSink sink;
    private final Options options;
    private final AllocationSpec spec;
    private final String[] dimensionNames;
    private final String ruleSha256;

    private final Map<String, RowStore> inbox = new HashMap<>();
    private final Map<String, Router> routers = new HashMap<>();
    private final List<RowStore> stores = new ArrayList<>();
    private final SpillSpace spill = new SpillSpace();
    private final Map<String, String> dataSha256 = new LinkedHashMap<>();
    private final Map<String, DriverTable> driverCache = new HashMap<>();
    private final List<RunSummary.StageSummary> stageSummaries = new ArrayList<>();
    private final List<RunSink.Warning> warnings = new ArrayList<>();

    private long inputAmount;
    private long grandTotal;
    private long outputRowsTotal;
    private long terminalRows;
    private long ledgerRows;
    private long poolChecks;
    private long stageOutRows;

    private Executor(Plan plan, String ruleSha256, AllocationInput input, RunSink sink, Options options) {
        this.plan = plan;
        this.ruleSha256 = ruleSha256 == null ? "" : ruleSha256;
        this.input = input;
        this.sink = sink;
        this.options = options;
        this.spec = plan.spec();
        this.dimensionNames = spec.dimensions().toArray(new String[0]);
        for (Map.Entry<String, List<Plan.Route>> entry : plan.outgoing().entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                routers.put(entry.getKey(), new Router(entry.getValue()));
            }
        }
    }

    /** Runs the plan to completion. */
    public static RunSummary execute(Plan plan, String ruleSha256, AllocationInput input, RunSink sink,
                                     Options options) {
        return new Executor(plan, ruleSha256, input, sink, options).run();
    }

    /** Runs without a rule digest; the summary records an empty hash. */
    public static RunSummary execute(Plan plan, AllocationInput input, RunSink sink, Options options) {
        return execute(plan, "", input, sink, options);
    }

    /** Convenience for tests and small jobs: sequential, no evidence beyond results. */
    public static RunSummary execute(Plan plan, AllocationInput input, RunSink sink) {
        return execute(plan, input, sink, Options.sequential());
    }

    private RunSummary run() {
        String startedUtc = RFC3339.format(Instant.now());
        try {
            seedSources();
            for (StageSpec stage : plan.ordered()) {
                runStage(stage);
            }
            if (grandTotal != inputAmount) {
                throw new AllocationException(ErrorCode.CONSERVATION_FAILED,
                        "DAG final total " + grandTotal + " != input total " + inputAmount);
            }
            return new RunSummary(dataSha256, AllocationSpec.SCHEMA, "allocation-java", spec.name(),
                    ruleSha256, startedUtc, stageSummaries, inputAmount, grandTotal, spec.precision(),
                    poolChecks, outputRowsTotal, terminalRows, ledgerRows, "PASS", warnings);
        } finally {
            for (RowStore store : stores) {
                store.close();
            }
            stores.clear();
            spill.close();
        }
    }

    private RowStore newStore() {
        RowStore store = new RowStore(spill, options.maxRowsInMemory());
        stores.add(store);
        return store;
    }

    // ---------------------------------------------------------------------------------------------
    // Source seeding
    // ---------------------------------------------------------------------------------------------

    private void seedSources() {
        List<String> sources = new ArrayList<>();
        for (String source : plan.outgoing().keySet()) {
            if (plan.stage(source) == null) {
                sources.add(source);
            }
        }
        sources.sort(Utf8Order::compare);
        for (String source : sources) {
            recordFingerprint(source, null);
            Router router = routers.get(source);
            // Row identifiers embed the ordinal of each row in its dataset, so the count is per source
            // and advances for every row read, including rows a routing share drops.
            int[] ordinal = {0};
            input.forEachAmount(source, row -> {
                ordinal[0]++;
                inputAmount = Overflow.addExact(inputAmount, row.amount());
                routeRow(row.withIds(new String[]{RowIds.ofSource(source, row.firstId(), ordinal[0])}), router);
            });
        }
    }

    private void recordFingerprint(String dataset, String weightField) {
        String label = input.label(dataset, weightField);
        if (dataSha256.containsKey(label)) {
            return;
        }
        String hash = input.fingerprint(dataset, weightField);
        if (hash != null) {
            dataSha256.put(label, hash);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Stage execution
    // ---------------------------------------------------------------------------------------------

    private void runStage(StageSpec stage) {
        RowStore inputs = inbox.remove(stage.id());
        RowStore buffered = null;
        try {
            long before = inputs == null ? 0L : inputs.total();
            boolean terminal = plan.isTerminal(stage.id());
            stageOutRows = 0;
            if (!terminal) {
                buffered = newStore();
            }
            Emitter emitter = new Emitter(stage.id(), terminal, buffered);

            if (!stage.isEnabled() || stage.operationOrDefault() != StageOperation.ALLOCATE) {
                if (stage.operationOrDefault() == StageOperation.MERGE) {
                    emitMergeEvidence(stage, inputs);
                    // Coalescing is where a merge stage earns its name: duplicates are summed and the
                    // result is emitted in coordinate order, which is what the reference does.
                    for (Row row : coalesce(inputs).values()) {
                        emitter.emit(row.withIds(new String[]{
                                RowIds.of(stage.id(), "MERGED", "", row.coord().key())}));
                    }
                } else if (inputs != null) {
                    inputs.forEach(emitter::emit);
                }
            } else {
                // Runs even when the stage received nothing: a rule with no input still has to report
                // the target members that would have received nothing, exactly as it always has.
                runAllocatingStage(stage, inputs, emitter);
            }

            long after = emitter.total;
            if (before != after) {
                throw new AllocationException(ErrorCode.CONSERVATION_FAILED,
                        "total mismatch " + before + " vs " + after, stage.id(), null, null, null);
            }
            stageSummaries.add(new RunSummary.StageSummary(stage.id(), before, after,
                    emitter.pools, stageOutRows));
            outputRowsTotal += stageOutRows;

            if (!terminal) {
                Map<String, Row> merged = coalesce(buffered);
                linkCoalesced(buffered, merged, stage.id());
                routeRows(merged.values(), routers.get(stage.id()));
            }
        } finally {
            if (buffered != null) {
                buffered.close();
            }
            if (inputs != null) {
                inputs.close();
            }
        }
    }

    /**
     * Emits a merge stage's evidence.
     *
     * <p>Two passes, not one, because the ledger has an order: every {@code MERGE} lineage edge comes
     * before every membership line. A {@link RowStore} can be replayed, so that costs a second read of
     * the buffer instead of a second copy of it in memory.
     */
    private void emitMergeEvidence(StageSpec stage, RowStore inputs) {
        if (inputs == null) {
            return;
        }
        inputs.forEach(row -> {
            String mergedId = RowIds.of(stage.id(), "MERGED", "", row.coord().key());
            for (String parent : row.ids()) {
                lineage(stage.id(), "MERGE", parent, mergedId, row.amount());
            }
        });
        inputs.forEach(row -> {
            String pool = row.coord().key();
            for (String id : row.ids()) {
                sink.membership(new RunSink.MembershipLine(stage.id(), pool, id, row.amount()));
            }
        });
    }

    private void runAllocatingStage(StageSpec stage, RowStore inputs, Emitter emitter) {
        Map<String, MemberFilter> sourceFilters = new LinkedHashMap<>();
        for (Map.Entry<String, io.allocation.kernel.spec.FilterSpec> entry
                : stage.source().filters().entrySet()) {
            sourceFilters.put(entry.getKey(),
                    MemberFilter.compile(entry.getKey(), entry.getValue(), spec.hierarchies()));
        }
        int[] filterPositions = new int[sourceFilters.size()];
        MemberFilter[] filterValues = new MemberFilter[sourceFilters.size()];
        int fi = 0;
        for (Map.Entry<String, MemberFilter> entry : sourceFilters.entrySet()) {
            filterPositions[fi] = spec.dimensionIndex(entry.getKey());
            filterValues[fi] = entry.getValue();
            fi++;
        }

        Map<String, PoolBucket> groups = new LinkedHashMap<>();
        if (inputs != null) {
            inputs.forEach(row -> {
                if (!matches(row.coord(), filterPositions, filterValues)) {
                    emitter.emit(row);
                    return;
                }
                Coord pool = poolCoord(row.coord(), stage);
                PoolBucket bucket = groups.get(pool.key());
                if (bucket == null) {
                    bucket = new PoolBucket(pool);
                    groups.put(pool.key(), bucket);
                }
                for (String id : row.ids()) {
                    sink.membership(new RunSink.MembershipLine(stage.id(), pool.key(), id, row.amount()));
                }
                bucket.total = Overflow.addExact(bucket.total, row.amount());
            });
        }
        emitter.pools = groups.size();

        List<PoolAllocator.RuleContext> contexts = buildRuleContexts(stage);
        List<String> keys = new ArrayList<>(groups.keySet());
        keys.sort(Utf8Order::compare);

        forEachPool(keys, key -> {
            PoolBucket bucket = groups.get(key);
            return PoolAllocator.allocate(stage, spec, dimensionNames, key, bucket.coord, bucket.total, contexts);
        }, (key, outcome) -> {
            for (Row row : outcome.outputs()) {
                emitter.emit(row);
            }
            for (RunSink.LedgerLine line : outcome.ledger()) {
                sink.ledger(line);
                ledgerRows++;
            }
            sink.reconciliation(new RunSink.ReconciliationLine(stage.id(), key,
                    outcome.input(), outcome.output(), 0L, "PASS"));
            poolChecks++;
        });
    }

    private List<PoolAllocator.RuleContext> buildRuleContexts(StageSpec stage) {
        List<PoolAllocator.RuleContext> contexts = new ArrayList<>();
        for (AllocationRule rule : stage.allocations()) {
            if (!rule.isEnabled()) {
                continue;
            }
            String cacheKey = rule.driverDataset() + '\0' + rule.weightField();
            DriverTable drivers = driverCache.get(cacheKey);
            if (drivers == null) {
                drivers = loadDrivers(rule.driverDataset(), rule.weightField());
                driverCache.put(cacheKey, drivers);
                recordFingerprint(rule.driverDataset(), rule.weightField());
            }
            long share = io.allocation.kernel.money.Decimal.parseScaled(rule.effectiveShare(), 6);
            PoolAllocator.RuleContext context =
                    PoolAllocator.build(rule, share, drivers, stage, spec, dimensionNames);
            contexts.add(context);
            checkTargetCompleteness(stage, context);
            warnOnDuplicateDrivers(stage, rule, context);
        }
        return contexts;
    }

    /**
     * Reads a driver dataset straight into a column table.
     *
     * <p>The rows are never held: each one is appended and dropped, so the only thing that grows is the
     * table itself. That matters because a driver table is compulsory resident memory — it cannot spill,
     * since a pool needs random access to its targets' weights — so its representation is the floor of a
     * wide allocation.
     */
    private DriverTable loadDrivers(String dataset, String weightField) {
        DriverTable.Builder builder = DriverTable.builder(dimensionNames.length);
        input.forEachDriver(dataset, weightField,
                row -> builder.add(row.coord().members(), row.weight()));
        return builder.build();
    }

    private void checkTargetCompleteness(StageSpec stage, PoolAllocator.RuleContext context) {
        int[] derived = new int[dimensionNames.length];
        int derivedCount = 0;
        for (int i = 0; i < dimensionNames.length; i++) {
            TargetSpec target = stage.target().get(dimensionNames[i]);
            if (target != null && target.isDriverDerived()) {
                derived[derivedCount++] = i;
            }
        }
        if (derivedCount == 0) {
            return;
        }
        // Members are interned during parsing, so these sets stay tiny regardless of row count.
        Set<String>[] present = new Set[derivedCount];
        for (int i = 0; i < derivedCount; i++) {
            present[i] = new HashSet<>();
        }
        DriverTable drivers = context.drivers;
        for (PoolAllocator.Rows bucket : context.buckets.values()) {
            for (int b = 0; b < bucket.size; b++) {
                int row = bucket.data[b];
                for (int i = 0; i < derivedCount; i++) {
                    int position = derived[i];
                    if (drivers.has(row, position)) {
                        present[i].add(drivers.member(row, position));
                    }
                }
            }
        }
        for (int i = 0; i < derivedCount; i++) {
            int position = derived[i];
            TargetSpec target = stage.target().get(dimensionNames[position]);
            Set<String> declared = io.allocation.kernel.plan.Hierarchy.expand(
                    dimensionNames[position], target.include(), spec.hierarchies());
            for (String member : declared) {
                if (present[i].contains(member)) {
                    continue;
                }
                String message = "declared target " + member + " on " + dimensionNames[position]
                        + " has no driver weight in rule " + context.rule.id()
                        + "; it will silently receive nothing";
                if (options.blockOnMissingTarget()) {
                    throw new AllocationException(ErrorCode.RULE_INVALID, message,
                            stage.id(), context.rule.id(), null, null);
                }
                RunSink.Warning warning = new RunSink.Warning(
                        "W_TARGET_MISSING_DRIVER", message, stage.id(), context.rule.id());
                warnings.add(warning);
                sink.warning(warning);
            }
        }
    }

    /**
     * Reports driver coordinates that appear more than once.
     *
     * <p>Duplicates are summed by the allocator, which is correct but rarely intended: it usually means
     * an export joined two tables and multiplied a partner's weight. Counting is done bucket by bucket
     * so that peak memory stays proportional to the largest bucket, not to the whole file.
     */
    private void warnOnDuplicateDrivers(StageSpec stage, AllocationRule rule,
                                        PoolAllocator.RuleContext context) {
        int duplicates = 0;
        DriverTable drivers = context.drivers;
        for (PoolAllocator.Rows bucket : context.buckets.values()) {
            if (bucket.size < 2) {
                continue;
            }
            // One coordinate object per row instead of one key string per row: the check is the same,
            // the allocation is a fraction of it, and nothing is retained past this loop.
            Set<Coord> seen = new HashSet<>(bucket.size * 2);
            for (int b = 0; b < bucket.size; b++) {
                if (!seen.add(drivers.coord(bucket.data[b]))) {
                    duplicates++;
                }
            }
        }
        if (duplicates > 0) {
            RunSink.Warning warning = new RunSink.Warning("W_DUPLICATE_DRIVER_ROWS",
                    duplicates + " driver row(s) repeat a coordinate in rule " + rule.id()
                            + "; their weights are summed, which usually indicates an export defect",
                    stage.id(), rule.id());
            warnings.add(warning);
            sink.warning(warning);
        }
    }

    private Coord poolCoord(Coord source, StageSpec stage) {
        String[] members = new String[dimensionNames.length];
        for (int i = 0; i < dimensionNames.length; i++) {
            Grouping grouping = stage.source().grouping().get(dimensionNames[i]);
            members[i] = grouping == Grouping.AS_IS
                    ? source.member(i)
                    : AllocationSpec.POOL_MEMBER;
        }
        return new Coord(members);
    }

    private static boolean matches(Coord coord, int[] positions, MemberFilter[] filters) {
        for (int i = 0; i < positions.length; i++) {
            if (!filters[i].test(coord.memberOrEmpty(positions[i]))) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Routing, merging, lineage
    // ---------------------------------------------------------------------------------------------

    private void routeRows(Iterable<Row> rows, Router router) {
        if (router == null) {
            return;
        }
        for (Row row : rows) {
            routeRow(row, router);
        }
    }

    /**
     * Routes one row along its edges.
     *
     * <p>A zero share is a real outcome, not a rounding accident: a row whose amount is zero, or whose
     * split assigns it nothing, contributes no membership and no amount downstream. Dropping it here
     * keeps that rule in one place instead of spreading it across stages.
     */
    private void routeRow(Row row, Router router) {
        if (router == null) {
            return;
        }
        if (row.amount() == 0) {
            return;
        }
        if (router.passthrough) {
            inboxFor(router.to[0]).add(row);
            return;
        }
        long[] values = Apportion.split(row.amount(), router.shares, router.to, false);
        for (int i = 0; i < router.to.length; i++) {
            if (values[i] != 0) {
                inboxFor(router.to[i]).add(new Row(row.coord(), values[i], row.ids()));
            }
        }
    }

    private RowStore inboxFor(String stageId) {
        return inbox.computeIfAbsent(stageId, key -> newStore());
    }

    /**
     * Coalesces a stage's output: duplicate coordinates are summed, and the result is ordered by
     * coordinate in UTF-8 order.
     *
     * <p>The first row for a coordinate wins, so a coalesced row keeps the identifier of the row that
     * created it. That is not an implementation detail: it decides which upstream identifier appears in
     * the {@code COALESCE} lineage edge.
     */
    private static Map<String, Row> coalesce(RowStore rows) {
        TreeMap<String, Row> merged = new TreeMap<>(Utf8Order::compare);
        if (rows == null) {
            return merged;
        }
        rows.forEach(row -> {
            String key = row.coord().key();
            Row existing = merged.get(key);
            if (existing == null) {
                merged.put(key, row);
            } else {
                merged.put(key, existing.withAmount(Overflow.addExact(existing.amount(), row.amount())));
            }
        });
        return merged;
    }

    private void linkCoalesced(RowStore inputs, Map<String, Row> merged, String stageId) {
        if (inputs == null) {
            return;
        }
        inputs.forEach(row -> {
            Row child = merged.get(row.coord().key());
            if (child == null) {
                return;
            }
            for (String parent : row.ids()) {
                lineage(stageId, "COALESCE", parent, child.firstId(), row.amount());
            }
        });
    }

    private void lineage(String stageId, String event, String parent, String child, long amount) {
        if (parent.equals(child)) {
            return;
        }
        sink.lineage(new RunSink.LineageLine(stageId, event, parent, child, amount));
    }

    // ---------------------------------------------------------------------------------------------
    // Bounded, ordered pool parallelism
    // ---------------------------------------------------------------------------------------------

    private void forEachPool(List<String> keys, PoolWork work, PoolConsumer consume) {
        if (keys.isEmpty()) {
            return;
        }
        int requested = options.workers();
        if (requested < 1) {
            requested = 1;
        }
        int ceiling = Math.min(32, Runtime.getRuntime().availableProcessors() * 4);
        int workers = Math.min(requested, ceiling);
        workers = Math.min(workers, keys.size());

        if (workers <= 1) {
            for (String key : keys) {
                PoolAllocator.Outcome outcome = work.compute(key);
                consume.accept(key, outcome);
            }
            return;
        }

        ExecutorService service = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "allocation-pool-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            int batchSize = workers * 2;
            for (int start = 0; start < keys.size(); start += batchSize) {
                int end = Math.min(start + batchSize, keys.size());
                List<Future<PoolAllocator.Outcome>> futures = new ArrayList<>(end - start);
                for (int i = start; i < end; i++) {
                    String key = keys.get(i);
                    futures.add(service.submit(() -> work.compute(key)));
                }
                for (int i = 0; i < futures.size(); i++) {
                    PoolAllocator.Outcome outcome;
                    try {
                        outcome = futures.get(i).get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AllocationException(ErrorCode.INTERNAL, "interrupted while allocating", e);
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof AllocationException allocationException) {
                            throw allocationException;
                        }
                        throw new AllocationException(ErrorCode.INTERNAL,
                                "pool computation failed: " + cause, cause);
                    }
                    consume.accept(keys.get(start + i), outcome);
                }
            }
        } finally {
            service.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface PoolWork {
        PoolAllocator.Outcome compute(String key);
    }

    @FunctionalInterface
    private interface PoolConsumer {
        void accept(String key, PoolAllocator.Outcome outcome);
    }

    /** A pool as it is being accumulated. */
    private static final class PoolBucket {
        final Coord coord;
        long total;

        PoolBucket(Coord coord) {
            this.coord = coord;
        }
    }

    /**
     * One source's outgoing edges, with the split precomputed.
     *
     * <p>Built once per source rather than per row: a million-row dataset routed through three edges
     * would otherwise allocate three million arrays to hold three numbers each.
     */
    private static final class Router {
        final String[] to;
        final long[] shares;
        final boolean passthrough;

        Router(List<Plan.Route> edges) {
            this.to = new String[edges.size()];
            this.shares = new long[edges.size()];
            for (int i = 0; i < edges.size(); i++) {
                to[i] = edges.get(i).to();
                shares[i] = edges.get(i).share();
            }
            this.passthrough = edges.size() == 1 && edges.get(0).share() == AllocationSpec.UNIT;
        }
    }

    /** Collects a stage's output and its running total. */
    private final class Emitter {
        private final String stageId;
        private final boolean terminal;
        private final RowStore buffer;
        long total;
        int pools;

        Emitter(String stageId, boolean terminal, RowStore buffer) {
            this.stageId = stageId;
            this.terminal = terminal;
            this.buffer = buffer;
        }

        void emit(Row row) {
            total = Overflow.addExact(total, row.amount());
            stageOutRows++;
            if (terminal) {
                terminalRows++;
                sink.result(new RunSink.ResultLine(stageId, row.firstId(), row.coord(), row.amount()));
                grandTotal = Overflow.addExact(grandTotal, row.amount());
            } else {
                buffer.add(row);
            }
        }
    }
}
