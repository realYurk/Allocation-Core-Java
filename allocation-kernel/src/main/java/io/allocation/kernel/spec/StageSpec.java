package io.allocation.kernel.spec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One node of the allocation DAG.
 *
 * @param operation        {@code null} means {@link StageOperation#ALLOCATE}
 * @param id               stable stage identifier
 * @param inputs           incoming edges; at least one is required
 * @param source           how rows are filtered and grouped
 * @param target           per-dimension target derivation, must cover every dimension
 * @param conservationKeys dimensions asserted unchanged across the stage
 * @param allocations      allocation rules, required when the stage allocates
 * @param enabled          {@code null} means enabled; a disabled stage behaves as {@code PASS}
 */
public record StageSpec(
        StageOperation operation,
        String id,
        List<InputEdge> inputs,
        SourceSpec source,
        Map<String, TargetSpec> target,
        List<String> conservationKeys,
        List<AllocationRule> allocations,
        Boolean enabled) {

    public StageSpec {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        Map<String, TargetSpec> targetCopy = new LinkedHashMap<>();
        if (target != null) {
            targetCopy.putAll(target);
        }
        target = Map.copyOf(targetCopy);
        conservationKeys = conservationKeys == null ? List.of() : List.copyOf(conservationKeys);
        allocations = allocations == null ? List.of() : List.copyOf(allocations);
    }

    public StageOperation operationOrDefault() {
        return operation == null ? StageOperation.ALLOCATE : operation;
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public TargetSpec target(String dimension) {
        TargetSpec spec = target.get(dimension);
        return spec == null ? TargetSpec.source() : spec;
    }

    /** True when this stage actually splits pools. */
    public boolean allocates() {
        return isEnabled() && operationOrDefault() == StageOperation.ALLOCATE;
    }
}
