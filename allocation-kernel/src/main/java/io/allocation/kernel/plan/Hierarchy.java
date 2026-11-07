package io.allocation.kernel.plan;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Expands hierarchy members into their leaf sets, for filters and include lists.
 *
 * <p>Expansion is cycle-checked. A cycle in a hierarchy would otherwise expand forever, and the failure
 * mode — a run that hangs rather than a run that fails — is the worst possible outcome for a nightly
 * close.
 */
public final class Hierarchy {

    private Hierarchy() {
    }

    /**
     * Expands {@code elements} into leaves under {@code dimension}.
     *
     * @param dimension  the dimension whose hierarchy applies
     * @param elements   members to expand; empty input yields an empty set
     * @param hierarchies declared hierarchies, possibly empty
     * @return leaf members
     */
    public static Set<String> expand(String dimension, List<String> elements,
                                     Map<String, Map<String, List<String>>> hierarchies) {
        Set<String> result = new LinkedHashSet<>();
        if (elements == null || elements.isEmpty()) {
            return result;
        }
        Map<String, List<String>> tree = hierarchies.get(dimension);
        if (tree == null || tree.isEmpty()) {
            result.addAll(elements);
            return result;
        }
        Deque<String> path = new ArrayDeque<>();
        for (String element : elements) {
            walk(dimension, element, tree, result, path, new HashSet<>());
        }
        return result;
    }

    private static void walk(String dimension, String member, Map<String, List<String>> tree,
                             Set<String> out, Deque<String> path, Set<String> onPath) {
        if (!onPath.add(member)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(member);
            throw new AllocationException(ErrorCode.RULE_INVALID,
                    "hierarchy cycle: " + dimension + "/" + String.join(" -> ", cycle));
        }
        path.addLast(member);
        List<String> children = tree.get(member);
        if (children == null) {
            out.add(member);
        } else if (children.isEmpty()) {
            throw new AllocationException(ErrorCode.RULE_INVALID,
                    "empty hierarchy " + dimension + "/" + member);
        } else {
            for (String child : children) {
                walk(dimension, child, tree, out, path, onPath);
            }
        }
        path.removeLast();
        onPath.remove(member);
    }
}
