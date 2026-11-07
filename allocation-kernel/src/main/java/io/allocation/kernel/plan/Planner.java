package io.allocation.kernel.plan;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.money.Decimal;
import io.allocation.kernel.spec.AllocationRule;
import io.allocation.kernel.spec.AllocationSpec;
import io.allocation.kernel.spec.Grouping;
import io.allocation.kernel.spec.InputEdge;
import io.allocation.kernel.spec.NegativeDriverPolicy;
import io.allocation.kernel.spec.StageSpec;
import io.allocation.kernel.spec.TargetMode;
import io.allocation.kernel.spec.TargetSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Structural validation of a rule document, producing an executable {@link Plan}.
 *
 * <p>Every check here mirrors the reference implementation, so that a document accepted by one is
 * accepted by both. The checks are deliberately pedantic: each corresponds to a way a rule file can
 * look plausible while producing wrong money.
 */
public final class Planner {

    private Planner() {
    }

    /** Compiles and validates a spec. */
    public static Plan compile(AllocationSpec spec) {
        require(AllocationSpec.SCHEMA.equals(spec.schema()), "schema must be " + AllocationSpec.SCHEMA);
        String policy = spec.roundingPolicy();
        if (policy != null && !policy.isEmpty()
                && !policy.equals(AllocationSpec.ROUNDING_LARGEST_REMAINDER_IDENTITY)) {
            require(false, "unsupported rounding_policy \"" + policy + "\"; legacy uses "
                    + AllocationSpec.ROUNDING_LARGEST_REMAINDER_IDENTITY);
        }
        require(spec.precision() >= 0 && spec.precision() <= 6, "precision must be 0..6");
        require(!spec.dimensions().isEmpty() && spec.dimensions().size() <= 30, "expected 1..30 dimensions");

        Set<String> dimensions = new HashSet<>();
        for (String dimension : spec.dimensions()) {
            require(!dimension.isEmpty() && !dimensions.contains(dimension) && dimension.indexOf('\0') < 0,
                    "invalid/duplicate dimension \"" + dimension + "\"");
            dimensions.add(dimension);
        }
        for (String immutable : spec.immutableDimensions()) {
            require(dimensions.contains(immutable), "unknown immutable dimension " + immutable);
        }
        require(!spec.stages().isEmpty() && spec.stages().size() <= 100, "expected 1..100 stages");

        Map<String, StageSpec> stages = new LinkedHashMap<>();
        Map<String, Integer> dependencies = new HashMap<>();
        Map<String, List<String>> edges = new HashMap<>();

        for (StageSpec stage : spec.stages()) {
            String where = "stage " + stage.id();
            require(!stage.id().isEmpty() && !stage.id().equals("$previous") && !stages.containsKey(stage.id()),
                    "duplicate/invalid stage ID " + stage.id());
            require(!spec.amountDatasets().containsKey(stage.id()),
                    "stage ID conflicts with input dataset: " + stage.id());
            stages.put(stage.id(), stage);
            dependencies.put(stage.id(), 0);
            require(!stage.inputs().isEmpty(), where + " has no input edges");

            for (String key : stage.source().grouping().keySet()) {
                require(dimensions.contains(key), where + " unknown grouping dimension " + key);
            }
            for (String key : stage.target().keySet()) {
                require(dimensions.contains(key), where + " unknown target dimension " + key);
            }
            for (String key : stage.conservationKeys()) {
                require(dimensions.contains(key), where + " unknown conservation dimension " + key);
            }

            for (String dimension : spec.dimensions()) {
                Grouping grouping = stage.source().grouping().get(dimension);
                require(grouping != null, where + " dimension " + dimension + " invalid grouping");

                TargetSpec target = stage.target().get(dimension);
                require(target != null, where + " dimension " + dimension + " missing target");

                switch (target.mode()) {
                    case SOURCE -> require(grouping == Grouping.AS_IS,
                            where + ": SOURCE target requires AS_IS on " + dimension);
                    case ELEMENT -> require(target.value() != null && !target.value().isEmpty(),
                            where + ": ELEMENT missing value on " + dimension);
                    case INCLUDE, INCLUDE_EXCLUDE -> require(!target.include().isEmpty(),
                            where + ": INCLUDE missing members on " + dimension);
                }

                boolean conserved = spec.isImmutable(dimension) || stage.conservationKeys().contains(dimension);
                if (conserved) {
                    require(grouping == Grouping.AS_IS && target.mode() == TargetMode.SOURCE,
                            where + ": conservation dimension " + dimension + " must be AS_IS + SOURCE");
                }
            }

            for (String key : stage.source().filters().keySet()) {
                require(dimensions.contains(key), where + " unknown source filter " + key);
            }

            if (stage.allocates()) {
                validateAllocations(spec, stage, where, dimensions);
            }
        }

        Map<String, List<Plan.Route>> outgoing = new LinkedHashMap<>();
        for (StageSpec stage : spec.stages()) {
            Set<String> seenInput = new HashSet<>();
            String where = "stage " + stage.id();
            for (InputEdge edge : stage.inputs()) {
                require(edge.from() != null && !edge.from().isEmpty() && seenInput.add(edge.from()),
                        where + " duplicate/blank input edge");
                require(!edge.from().equals(stage.id()), where + " self cycle");
                if (stages.containsKey(edge.from())) {
                    dependencies.merge(stage.id(), 1, Integer::sum);
                    edges.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(stage.id());
                } else {
                    require(spec.amountDatasets().containsKey(edge.from()),
                            where + " unknown input " + edge.from());
                }
                long share = parseShare(edge.effectiveShare(),
                        where + " invalid edge share " + edge.effectiveShare());
                require(share > 0 && share <= AllocationSpec.UNIT,
                        where + " invalid edge share " + edge.effectiveShare());
                outgoing.computeIfAbsent(edge.from(), k -> new ArrayList<>())
                        .add(new Plan.Route(stage.id(), share));
            }
        }

        Map<String, List<Plan.Route>> sortedOutgoing = new LinkedHashMap<>();
        for (Map.Entry<String, List<Plan.Route>> entry : outgoing.entrySet()) {
            entry.getValue().sort((a, b) -> a.to().compareTo(b.to()));
            long sum = 0;
            for (Plan.Route route : entry.getValue()) {
                sum += route.share();
            }
            require(sum == AllocationSpec.UNIT,
                    "source " + entry.getKey() + " outgoing shares must total exactly 1 (actual "
                            + sum + "/" + AllocationSpec.UNIT + ")");
            sortedOutgoing.put(entry.getKey(), List.copyOf(entry.getValue()));
        }

        return new Plan(spec, topologicalOrder(stages, dependencies, edges), sortedOutgoing);
    }

    private static void validateAllocations(AllocationSpec spec, StageSpec stage,
                                            String where, Set<String> dimensions) {
        require(!stage.allocations().isEmpty(), where + " has no allocations");
        long shares = 0;
        int active = 0;
        Set<String> seen = new HashSet<>();
        for (AllocationRule rule : stage.allocations()) {
            if (!rule.isEnabled()) {
                continue;
            }
            active++;
            require(rule.id() != null && !rule.id().isEmpty() && seen.add(rule.id()),
                    where + " duplicate/empty allocation rule ID " + rule.id());
            require(rule.driverDataset() != null && !rule.driverDataset().isEmpty()
                            && rule.weightField() != null && !rule.weightField().isEmpty()
                            && spec.driverDatasets().containsKey(rule.driverDataset()),
                    where + " allocation " + rule.id() + " missing driver dataset/weight");
            if (rule.policy() == NegativeDriverPolicy.ALLOW_SIGNED) {
                String reason = rule.signedDriverReason() == null ? "" : rule.signedDriverReason().strip();
                int codePoints = reason.codePointCount(0, reason.length());
                require(isWellFormed(reason) && codePoints >= 8 && codePoints <= 256
                                && reason.indexOf('\r') < 0 && reason.indexOf('\n') < 0,
                        where + " allocation " + rule.id()
                                + ": ALLOW_SIGNED requires a business reason (8..256 chars, one line)");
            } else {
                require(rule.signedDriverReason() == null || rule.signedDriverReason().isEmpty(),
                        where + " allocation " + rule.id() + ": signed_driver_reason requires ALLOW_SIGNED");
            }
            long share = parseShare(rule.effectiveShare(),
                    where + " invalid allocation share " + rule.effectiveShare());
            require(share >= 0 && share <= AllocationSpec.UNIT,
                    where + " invalid allocation share " + rule.effectiveShare());
            shares += share;
            for (String key : rule.driverFilters().keySet()) {
                require(dimensions.contains(key), "allocation " + rule.id() + " unknown driver filter " + key);
            }
        }
        require(active != 0 && shares == AllocationSpec.UNIT,
                where + " allocation shares must total exactly 1");
    }

    private static long parseShare(String text, String message) {
        try {
            return Decimal.parseScaled(text, 6);
        } catch (AllocationException e) {
            throw new AllocationException(ErrorCode.RULE_INVALID, message);
        }
    }

    private static List<StageSpec> topologicalOrder(Map<String, StageSpec> stages,
                                                    Map<String, Integer> dependencies,
                                                    Map<String, List<String>> edges) {
        TreeSet<String> ready = new TreeSet<>();
        for (Map.Entry<String, Integer> entry : dependencies.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }
        List<StageSpec> ordered = new ArrayList<>(stages.size());
        while (!ready.isEmpty()) {
            String id = ready.pollFirst();
            ordered.add(stages.get(id));
            List<String> targets = edges.get(id);
            if (targets == null) {
                continue;
            }
            for (String to : targets) {
                int remaining = dependencies.merge(to, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(to);
                }
            }
        }
        require(ordered.size() == stages.size(), "stage graph contains a cycle");
        return ordered;
    }

    private static boolean isWellFormed(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AllocationException(ErrorCode.RULE_INVALID, message);
        }
    }
}
