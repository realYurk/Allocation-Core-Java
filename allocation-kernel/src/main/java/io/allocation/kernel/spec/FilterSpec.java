package io.allocation.kernel.spec;

import java.util.List;

/**
 * Include/exclude member lists, expanded through {@code hierarchies} before use.
 *
 * <p>An empty include list means "everything", which is why {@link #isUnrestricted()} exists: an empty
 * include is not the same as an empty selection.
 */
public record FilterSpec(List<String> include, List<String> exclude) {

    public FilterSpec {
        include = include == null ? List.of() : List.copyOf(include);
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
    }

    public static FilterSpec empty() {
        return new FilterSpec(List.of(), List.of());
    }

    public boolean isUnrestricted() {
        return include.isEmpty() && exclude.isEmpty();
    }
}
