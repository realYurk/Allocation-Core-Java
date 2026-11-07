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
 */
public final class Executor {

    /**
     * Execution options.
     *
     * @param workers              pool-level parallelism; {@code 0} or {@code 1} runs sequentially
     * @param blockOnMissingTarget treat "declared target has no driver weight" as fatal instead of a warning
     */
    public record Options(int workers, boolean blockOnMissingTarget) {

        public static Options sequential() {
            return new Options(1, false);
        }

        public static Options parallel(int workers) {
            return new Options(workers, false);
        }
    }

    /** Summary of a completed run. */
    private static final DateTimeFormatter RFC3339 =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final Plan plan;
    private final AllocationInput input;
    private final RunSink sink;
    private final Options options;
    private final AllocationSpec spec;
    private final String[] dimensionNames;
    private final String ruleSha256;

    private final Map<String, List<Row>> inbox = new HashMap<>();
    private final Map<String, String> dataSha256 = new LinkedHashMap<>();
    private final Map<String, List<DriverRow>> driverCache = new HashMap<>();
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
            List<Row> rows = input.readAmounts(source);
            long sum = 0L;
            for (Row row : rows) {
                sum = Overflow.addExact(sum, row.amount());
            }
            inputAmount = Overflow.addExact(inputAmount, sum);
            recordFingerprint(source, null);

            List<Row> identified = new ArrayList<>(rows.size());
            int ordinal = 0;
            for (Row row : rows) {
                ordinal++;
                identified.add(row.withIds(new String[]{RowIds.ofSource(source, row.firstId(), ordinal)}));
            }
            routeRows(identified, plan.outgoing().get(source));
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
        List<Row> inputs = inbox.remove(stage.id());
        if (inputs == null) {
            inputs = List.of();
        }
        long before = sumRows(inputs);
        boolean terminal = plan.isTerminal(stage.id());
        stageOutRows = 0;

        List<Row> buffered = new ArrayList<>();
        Emitter emitter = new Emitter(stage.id(), terminal, buffered);
        long after;

        if (!stage.isEnabled() || stage.operationOrDefault() != StageOperation.ALLOCATE) {
            if (stage.operationOrDefault() == StageOperation.MERGE) {
                emitMergeEvidence(stage, inputs);
                inputs = mergeRows(inputs);
                List<Row> relabelled = new ArrayList<>(inputs.size());
                for (Row row : inputs) {
                    relabelled.add(row.withIds(new String[]{
                            RowIds.of(stage.id(), "MERGED", "", row.coord().key())}));
                }
                inputs = relabelled;
            }
            for (Row row : inputs) {
                emitter.emit(row);
            }
        } else {
            runAllocatingStage(stage, inputs, emitter);
        }

        after = emitter.total;
        if (before != after) {
            throw new AllocationException(ErrorCode.CONSERVATION_FAILED,
                    "total mismatch " + before + " vs " + after, stage.id(), null, null, null);
        }
        stageSummaries.add(new RunSummary.StageSummary(stage.id(), before, after,
                emitter.pools, stageOutRows));
        outputRowsTotal += stageOutRows;

        if (!terminal) {
            List<Row> merged = mergeRows(buffered);
            linkCoalesced(buffered, merged, stage.id());
            routeRows(merged, plan.outgoing().get(stage.id()));
        }
    }

    private void emitMergeEvidence(StageSpec stage, List<Row> inputs) {
        for (Row row : inputs) {
            String mergedId = RowIds.of(stage.id(), "MERGED", "", row.coord().key());
            for (String parent : row.ids()) {
                lineage(stage.id(), "MERGE", parent, mergedId, row.amount());
            }
        }
        for (Row row : inputs) {
            String pool = row.coord().key();
            for (String id : row.ids()) {
                sink.membership(new RunSink.MembershipLine(stage.id(), pool, id, row.amount()));
            }
        }
    }

    private void runAllocatingStage(StageSpec stage, List<Row> inputs, Emitter emitter) {
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
        for (Row row : inputs) {
            if (!matches(row.coord(), filterPositions, filterValues)) {
                emitter.emit(row);
                continue;
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
            List<DriverRow> rows = driverCache.get(cacheKey);
            if (rows == null) {
                rows = input.readDrivers(rule.driverDataset(), rule.weightField());
                driverCache.put(cacheKey, rows);
                recordFingerprint(rule.driverDataset(), rule.weightField());
            }
            long share = io.allocation.kernel.money.Decimal.parseScaled(rule.effectiveShare(), 6);
            PoolAllocator.RuleContext context =
                    PoolAllocator.build(rule, share, rows, stage, spec, dimensionNames);
            contexts.add(context);
            checkTargetCompleteness(stage, context);
            warnOnDuplicateDrivers(stage, rule, context);
        }
        return contexts;
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
        for (List<DriverRow> bucket : context.buckets.values()) {
            for (DriverRow row : bucket) {
                for (int i = 0; i < derivedCount; i++) {
                    int position = derived[i];
                    if (row.coord().has(position)) {
                        present[i].add(row.coord().member(position));
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
        for (List<DriverRow> bucket : context.buckets.values()) {
            if (bucket.size() < 2) {
                continue;
            }
            Set<String> keys = new HashSet<>(bucket.size() * 2);
            for (DriverRow row : bucket) {
                if (!keys.add(row.coord().key())) {
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

    private void routeRows(List<Row> rows, List<Plan.Route> edges) {
        if (rows == null || rows.isEmpty() || edges == null || edges.isEmpty()) {
            return;
        }
        if (edges.size() == 1 && edges.get(0).share() == AllocationSpec.UNIT) {
            String to = edges.get(0).to();
            List<Row> existing = inbox.get(to);
            if (existing == null || existing.isEmpty()) {
                boolean hasZero = false;
                for (Row row : rows) {
                    if (row.amount() == 0) {
                        hasZero = true;
                        break;
                    }
                }
                if (!hasZero) {
                    inbox.put(to, rows);
                    return;
                }
            }
        }
        int n = edges.size();
        long[] shares = new long[n];
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            shares[i] = edges.get(i).share();
            names[i] = edges.get(i).to();
        }
        for (Row row : rows) {
            long[] values = Apportion.split(row.amount(), shares, names, false);
            for (int i = 0; i < n; i++) {
                if (values[i] != 0) {
                    inbox.computeIfAbsent(edges.get(i).to(), k -> new ArrayList<>())
                            .add(new Row(row.coord(), values[i], row.ids()));
                }
            }
        }
    }

    private List<Row> mergeRows(List<Row> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<String, Row> merged = new HashMap<>(rows.size() * 2);
        for (Row row : rows) {
            String key = row.coord().key();
            Row existing = merged.get(key);
            if (existing == null) {
                merged.put(key, row);
            } else {
                merged.put(key, existing.withAmount(Overflow.addExact(existing.amount(), row.amount())));
            }
        }
        String[] keys = merged.keySet().toArray(new String[0]);
        java.util.Arrays.sort(keys, Utf8Order::compare);
        List<Row> out = new ArrayList<>(keys.length);
        for (String key : keys) {
            out.add(merged.get(key));
        }
        return out;
    }

    private void linkCoalesced(List<Row> inputs, List<Row> output, String stageId) {
        Map<String, String> target = new HashMap<>(output.size() * 2);
        for (Row row : output) {
            if (row.ids().length > 0) {
                target.put(row.coord().key(), row.ids()[0]);
            }
        }
        for (Row row : inputs) {
            String child = target.get(row.coord().key());
            for (String parent : row.ids()) {
                lineage(stageId, "COALESCE", parent, child, row.amount());
            }
        }
    }

    private void lineage(String stageId, String event, String parent, String child, long amount) {
        if (parent.equals(child)) {
            return;
        }
        sink.lineage(new RunSink.LineageLine(stageId, event, parent, child, amount));
    }

    private static long sumRows(List<Row> rows) {
        long total = 0L;
        for (Row row : rows) {
            total = Overflow.addExact(total, row.amount());
        }
        return total;
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

    /** Collects a stage's output and its running total. */
    private final class Emitter {
        private final String stageId;
        private final boolean terminal;
        private final List<Row> buffer;
        long total;
        int pools;

        Emitter(String stageId, boolean terminal, List<Row> buffer) {
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
