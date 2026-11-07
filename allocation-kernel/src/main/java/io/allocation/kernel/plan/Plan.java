package io.allocation.kernel.plan;

import io.allocation.kernel.spec.AllocationSpec;
import io.allocation.kernel.spec.StageSpec;

import java.util.List;
import java.util.Map;

/**
 * A validated execution plan.
 *
 * <p>Produced only by {@link Planner#compile(AllocationSpec)}; the constructor is not public because an
 * unvalidated plan is a liability. {@code ordered} is a deterministic topological order — lexicographic
 * among all currently-ready stages — so two runs of the same document visit stages identically
 * regardless of declaration order or worker count.
 *
 * @param ordered  stages in execution order
 * @param outgoing outgoing edges per source (a stage id or an amount dataset name), sorted by target
 */
public record Plan(AllocationSpec spec, List<StageSpec> ordered, Map<String, List<Route>> outgoing) {

    /**
     * One outgoing edge.
     *
     * @param to    destination stage id
     * @param share fraction of each upstream row routed here, scaled by 10^6
     */
    public record Route(String to, long share) {
    }

    public Plan {
        ordered = List.copyOf(ordered);
        outgoing = Map.copyOf(outgoing);
    }

    /** True when the stage has no outgoing edges, so its output is terminal. */
    public boolean isTerminal(String stageId) {
        List<Route> routes = outgoing.get(stageId);
        return routes == null || routes.isEmpty();
    }

    /** Stage ids in execution order. */
    public List<String> stageIds() {
        return ordered.stream().map(StageSpec::id).toList();
    }

    /** Returns the stage with the given id, or {@code null}. */
    public StageSpec stage(String id) {
        for (StageSpec stage : ordered) {
            if (stage.id().equals(id)) {
                return stage;
            }
        }
        return null;
    }
}
