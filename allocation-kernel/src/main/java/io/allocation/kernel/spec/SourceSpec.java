package io.allocation.kernel.spec;

import java.util.LinkedHashMap;
import java.util.Map;

/** How incoming rows are filtered and grouped into pools. */
public record SourceSpec(Map<String, Grouping> grouping, Map<String, FilterSpec> filters) {

    public SourceSpec {
        grouping = grouping == null ? Map.of() : Map.copyOf(grouping);
        Map<String, FilterSpec> copy = new LinkedHashMap<>();
        if (filters != null) {
            copy.putAll(filters);
        }
        filters = Map.copyOf(copy);
    }

    public Grouping groupingOf(String dimension) {
        Grouping g = grouping.get(dimension);
        return g == null ? Grouping.TOTAL : g;
    }
}
