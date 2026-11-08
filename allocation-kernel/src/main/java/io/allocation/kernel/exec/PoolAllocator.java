package io.allocation.kernel.exec;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.coord.Coord;
import io.allocation.kernel.coord.CoordKeys;
import io.allocation.kernel.coord.Utf8Order;
import io.allocation.kernel.money.Apportion;
import io.allocation.kernel.money.Overflow;
import io.allocation.kernel.plan.MemberFilter;
import io.allocation.kernel.spec.AllocationRule;
import io.allocation.kernel.spec.AllocationSpec;
import io.allocation.kernel.spec.FilterSpec;
import io.allocation.kernel.spec.StageSpec;
import io.allocation.kernel.spec.TargetMode;
import io.allocation.kernel.spec.TargetSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one pool into allocated rows.
 *
 * <p>This class holds the two decisions the whole system exists to make reproducible: <em>which targets
 * are eligible</em> and <em>how the remainder is settled</em>. Both are direct transcriptions of the
 * specification, because every "simplification" here has a financial consequence.
 */
final class PoolAllocator {

    private PoolAllocator() {
    }

    /**
     * Pre-computed, thread-safe state for one allocation rule.
     *
     * <p>Driver rows are bucketed once by the dimensions this rule inherits from its pool, so per-pool
     * work is a map lookup rather than a scan. Buckets are immutable after construction and shared
     * across worker threads.
     */
    static final class RuleContext {
        final AllocationRule rule;
        final long share;
        final int[] boundPositions;
        final DriverTable drivers;
        final Map<String, Rows> buckets;
        final MemberFilter[] driverFilters;
        final MemberFilter[] targetChecks;

        private RuleContext(AllocationRule rule, long share, int[] boundPositions, DriverTable drivers,
                            Map<String, Rows> buckets,
                            MemberFilter[] driverFilters, MemberFilter[] targetChecks) {
            this.rule = rule;
            this.share = share;
            this.boundPositions = boundPositions;
            this.drivers = drivers;
            this.buckets = buckets;
            this.driverFilters = driverFilters;
            this.targetChecks = targetChecks;
        }
    }

    /** A growable list of driver row indices, one bucket per pool. */
    static final class Rows {
        int[] data = new int[8];
        int size;

        void add(int row) {
            if (size == data.length) {
                data = java.util.Arrays.copyOf(data, size + (size >> 1) + 1);
            }
            data[size++] = row;
        }

        boolean isEmpty() {
            return size == 0;
        }
    }

    /** Eligible targets for one pool, ordered by canonical key. */
    record Candidates(Coord[] targets, long[] weights, String[] keys) {

        int size() {
            return keys.length;
        }
    }

    /** Everything one pool produced. Computed entirely off the coordinating thread. */
    record Outcome(long input, long output, List<Row> outputs, List<RunSink.LedgerLine> ledger) {
    }

    // ---------------------------------------------------------------------------------------------
    // Index construction
    // ---------------------------------------------------------------------------------------------

    /** Builds the per-rule driver index. Called once per rule per stage, never per pool. */
    static RuleContext build(AllocationRule rule, long share, DriverTable drivers,
                             StageSpec stage, AllocationSpec spec, String[] dimensionNames) {
        int dimCount = dimensionNames.length;
        MemberFilter[] driverFilters = new MemberFilter[dimCount];
        for (Map.Entry<String, FilterSpec> entry : rule.driverFilters().entrySet()) {
            int position = spec.dimensionIndex(entry.getKey());
            driverFilters[position] = MemberFilter.compile(dimensionNames[position],
                    entry.getValue(), spec.hierarchies());
        }

        MemberFilter[] targetChecks = new MemberFilter[dimCount];
        for (int i = 0; i < dimCount; i++) {
            TargetSpec target = stage.target().get(dimensionNames[i]);
            if (target != null && target.isDriverDerived()) {
                targetChecks[i] = MemberFilter.compile(dimensionNames[i],
                        new FilterSpec(target.include(), target.exclude()), spec.hierarchies());
            }
        }

        int rows = drivers.rowCount();
        int[] bound = new int[0];
        if (rows > 0) {
            int[] tmp = new int[dimCount];
            int count = 0;
            for (int i = 0; i < dimCount; i++) {
                TargetSpec target = stage.target().get(dimensionNames[i]);
                if (target != null && target.mode() == TargetMode.SOURCE && drivers.has(0, i)) {
                    tmp[count++] = i;
                }
            }
            bound = Arrays.copyOf(tmp, count);
        }

        Map<String, Rows> buckets = new HashMap<>();
        for (int row = 0; row < rows; row++) {
            if (!passesFilters(drivers, row, driverFilters, dimensionNames,
                    "driver filter requires missing column")) {
                continue;
            }
            if (!passesFilters(drivers, row, targetChecks, dimensionNames,
                    "target mode INCLUDE requires missing driver dimension")) {
                continue;
            }
            buckets.computeIfAbsent(CoordKeys.at(drivers.coord(row), bound), k -> new Rows()).add(row);
        }
        return new RuleContext(rule, share, bound, drivers, buckets, driverFilters, targetChecks);
    }

    private static boolean passesFilters(DriverTable drivers, int row, MemberFilter[] filters,
                                         String[] dimensionNames, String missingMessage) {
        for (int i = 0; i < filters.length; i++) {
            MemberFilter filter = filters[i];
            if (filter == null || filter.isNoOp()) {
                continue;
            }
            if (!drivers.has(row, i)) {
                throw new AllocationException(ErrorCode.RULE_INVALID,
                        missingMessage + " " + dimensionNames[i]);
            }
            if (!filter.test(drivers.member(row, i))) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Candidate construction
    // ---------------------------------------------------------------------------------------------

    /**
     * Derives the eligible targets for {@code pool} and each one's weight.
     *
     * <p>Repeated driver rows for the same target coordinate are <strong>summed</strong>, not
     * deduplicated. That is the reference behaviour, and it is also what makes a duplicated export row
     * double a partner's weight — which is why the engine reports duplicate driver rows as a warning
     * instead of silently accepting them.
     */
    static Candidates candidates(RuleContext context, StageSpec stage, Coord pool, String[] dimensionNames) {
        String bucketKey = CoordKeys.at(pool, context.boundPositions);
        Rows bucket = context.buckets.get(bucketKey);
        if (bucket == null || bucket.isEmpty()) {
            return new Candidates(new Coord[0], new long[0], new String[0]);
        }
        int dimCount = dimensionNames.length;
        DriverTable drivers = context.drivers;
        Map<String, Long> grouped = new HashMap<>(bucket.size * 2);
        Map<String, Coord> targets = new HashMap<>(bucket.size * 2);
        String[] members = new String[dimCount];

        for (int b = 0; b < bucket.size; b++) {
            int row = bucket.data[b];
            boolean good = true;
            for (int i = 0; i < dimCount; i++) {
                TargetSpec target = stage.target().get(dimensionNames[i]);
                if (target == null) {
                    throw new AllocationException(ErrorCode.RULE_INVALID,
                            "missing target for dimension " + dimensionNames[i], stage.id(), null, null, null);
                }
                switch (target.mode()) {
                    case SOURCE -> {
                        if (drivers.has(row, i) && !drivers.member(row, i).equals(pool.member(i))) {
                            good = false;
                        }
                        members[i] = pool.member(i);
                    }
                    case ELEMENT -> members[i] = target.value();
                    case INCLUDE, INCLUDE_EXCLUDE -> members[i] = drivers.member(row, i);
                }
                if (!good) {
                    break;
                }
            }
            if (!good) {
                continue;
            }
            long weight = drivers.weight(row);
            if (weight < 0 && !context.rule.allowsSigned()) {
                throw new AllocationException(ErrorCode.RULE_INVALID,
                        "negative driver requires ALLOW_SIGNED: " + context.rule.id(),
                        stage.id(), context.rule.id(), null, null);
            }
            Coord coord = new Coord(members.clone());
            Long existing = grouped.get(coord.key());
            if (existing == null) {
                grouped.put(coord.key(), weight);
                targets.put(coord.key(), coord);
            } else {
                grouped.put(coord.key(), Overflow.addExact(existing, weight));
            }
        }

        int n = grouped.size();
        String[] keys = grouped.keySet().toArray(new String[0]);
        Arrays.sort(keys, Utf8Order::compare);
        Coord[] outTargets = new Coord[n];
        long[] outWeights = new long[n];
        for (int i = 0; i < n; i++) {
            outTargets[i] = targets.get(keys[i]);
            outWeights[i] = grouped.get(keys[i]);
        }
        return new Candidates(outTargets, outWeights, keys);
    }

    // ---------------------------------------------------------------------------------------------
    // Allocation
    // ---------------------------------------------------------------------------------------------

    static Outcome allocate(StageSpec stage, AllocationSpec spec, String[] dimensionNames,
                            String poolKey, Coord pool, long total, List<RuleContext> contexts) {
        int ruleCount = contexts.size();
        long[] shares = new long[ruleCount];
        String[] names = new String[ruleCount];
        for (int i = 0; i < ruleCount; i++) {
            shares[i] = contexts.get(i).share;
            names[i] = contexts.get(i).rule.id();
        }
        long[] parts = Apportion.split(total, shares, names, false);

        List<Row> outputs = new ArrayList<>();
        List<RunSink.LedgerLine> ledger = new ArrayList<>();
        long output = 0L;

        for (int r = 0; r < ruleCount; r++) {
            long part = parts[r];
            if (part == 0) {
                continue;
            }
            RuleContext context = contexts.get(r);
            Candidates candidates = candidates(context, stage, pool, dimensionNames);
            if (candidates.size() == 0) {
                throw new AllocationException(ErrorCode.NO_ELIGIBLE_DRIVERS,
                        "has no eligible drivers", stage.id(), context.rule.id(), poolKey, null);
            }
            long[] values = Apportion.split(part, candidates.weights, candidates.keys,
                    context.rule.allowsSigned());
            long denominator = 0L;
            for (long weight : candidates.weights) {
                denominator = Overflow.addExact(denominator, weight);
            }
            for (int j = 0; j < values.length; j++) {
                long amount = values[j];
                if (amount == 0) {
                    continue;
                }
                Coord target = candidates.targets[j];
                for (String immutable : spec.immutableDimensions()) {
                    assertConserved(spec, target, pool, immutable, stage, context, poolKey);
                }
                for (String conserved : stage.conservationKeys()) {
                    assertConserved(spec, target, pool, conserved, stage, context, poolKey);
                }
                output = Overflow.addExact(output, amount);
                String outputId = RowIds.of(stage.id(), poolKey, context.rule.id(), candidates.keys[j]);
                outputs.add(new Row(target, amount, new String[]{outputId}));
                ledger.add(new RunSink.LedgerLine(stage.id(), context.rule.id(), poolKey,
                        candidates.keys[j], outputId, total, part, candidates.weights[j], denominator, amount));
            }
        }

        if (output != total) {
            throw new AllocationException(ErrorCode.CONSERVATION_FAILED,
                    "not conserved: " + total + " vs " + output, stage.id(), null, poolKey, null);
        }
        return new Outcome(total, output, outputs, ledger);
    }

    private static void assertConserved(AllocationSpec spec, Coord target, Coord pool, String dimension,
                                        StageSpec stage, RuleContext context, String poolKey) {
        int position = spec.dimensionIndex(dimension);
        if (position < 0) {
            throw new AllocationException(ErrorCode.RULE_INVALID,
                    "unknown conservation dimension " + dimension, stage.id(), context.rule.id(), poolKey, null);
        }
        if (!target.memberOrEmpty(position).equals(pool.memberOrEmpty(position))) {
            throw new AllocationException(ErrorCode.CONSERVATION_FAILED,
                    "conservation dimension " + dimension + " changed",
                    stage.id(), context.rule.id(), poolKey, null);
        }
    }
}
