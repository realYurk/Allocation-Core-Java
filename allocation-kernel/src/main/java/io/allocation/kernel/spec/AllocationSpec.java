package io.allocation.kernel.spec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A complete allocation rule document ({@code allocation-ir/2.0}).
 *
 * <p>Immutable by construction: a published rule is a control document, and a run must record the exact
 * SHA-256 of the bytes it executed. Mutating a spec in place would break that chain.
 *
 * @param precision            decimal places for amounts, 0..6
 * @param dimensions           ordered business dimensions; the order defines the canonical key
 * @param immutableDimensions  dimensions that may never change value
 * @param hierarchies         dimension to member to children, expanded before filtering
 * @param amountDatasets      dataset name to relative path, for source amounts
 * @param driverDatasets      dataset name to relative path, for weights
 */
public record AllocationSpec(
        String schema,
        String roundingPolicy,
        String name,
        int precision,
        List<String> dimensions,
        List<String> immutableDimensions,
        Map<String, Map<String, List<String>>> hierarchies,
        Map<String, String> amountDatasets,
        Map<String, String> driverDatasets,
        List<StageSpec> stages) {

    /** Accepted schema identifier. */
    public static final String SCHEMA = "allocation-ir/2.0";

    /** Accepted rounding policy; absent means this one. */
    public static final String ROUNDING_LARGEST_REMAINDER_IDENTITY = "LARGEST_REMAINDER_IDENTITY";

    /** Synthetic member standing in for a collapsed ({@code TOTAL}) dimension. */
    public static final String POOL_MEMBER = "__POOL__";

    /** Scale of share and weight fractions. */
    public static final long UNIT = 1_000_000L;

    public AllocationSpec {
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        immutableDimensions = immutableDimensions == null ? List.of() : List.copyOf(immutableDimensions);
        Map<String, Map<String, List<String>>> hierCopy = new LinkedHashMap<>();
        if (hierarchies != null) {
            for (Map.Entry<String, Map<String, List<String>>> entry : hierarchies.entrySet()) {
                Map<String, List<String>> inner = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> child : entry.getValue().entrySet()) {
                    inner.put(child.getKey(), List.copyOf(child.getValue()));
                }
                hierCopy.put(entry.getKey(), Map.copyOf(inner));
            }
        }
        hierarchies = Map.copyOf(hierCopy);
        amountDatasets = amountDatasets == null ? Map.of() : Map.copyOf(amountDatasets);
        driverDatasets = driverDatasets == null ? Map.of() : Map.copyOf(driverDatasets);
        stages = stages == null ? List.of() : List.copyOf(stages);
    }

    public boolean hasDimension(String name) {
        return dimensions.contains(name);
    }

    public boolean isImmutable(String name) {
        return immutableDimensions.contains(name);
    }

    /** Index of a dimension in the canonical key order, or -1. */
    public int dimensionIndex(String name) {
        return dimensions.indexOf(name);
    }
}
