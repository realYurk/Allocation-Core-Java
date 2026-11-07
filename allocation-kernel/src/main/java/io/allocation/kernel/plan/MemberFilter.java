package io.allocation.kernel.plan;

import io.allocation.kernel.spec.FilterSpec;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A compiled include/exclude test over one dimension's members.
 *
 * <p>Compiled once per stage instead of once per row: expanding a hierarchy is the expensive part, and
 * doing it per row turns a linear scan into a quadratic one on wide hierarchies.
 *
 * <p>An empty include list means "no restriction", not "nothing". This is the single most common
 * misreading of the rule format, so the flag is named for the behaviour rather than the emptiness.
 */
public record MemberFilter(Set<String> include, Set<String> exclude, boolean unrestricted) {

    public static MemberFilter compile(String dimension, FilterSpec filter,
                                       Map<String, Map<String, List<String>>> hierarchies) {
        Set<String> include = Hierarchy.expand(dimension, filter.include(), hierarchies);
        Set<String> exclude = Hierarchy.expand(dimension, filter.exclude(), hierarchies);
        return new MemberFilter(include, exclude, filter.include().isEmpty());
    }

    /** True when {@code member} passes. */
    public boolean test(String member) {
        if (unrestricted) {
            return !exclude.contains(member);
        }
        return include.contains(member) && !exclude.contains(member);
    }

    /** True when this filter rejects nothing, so it can be skipped entirely. */
    public boolean isNoOp() {
        return unrestricted && exclude.isEmpty();
    }
}
