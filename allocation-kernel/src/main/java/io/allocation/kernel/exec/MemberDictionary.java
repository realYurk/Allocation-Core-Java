package io.allocation.kernel.exec;

import java.util.HashMap;
import java.util.Map;

/**
 * Canonicalises dimension members so that identical text is one object.
 *
 * <p>A million-row driver file with five dimensions would otherwise allocate five million member
 * strings plus a million coordinate keys — measured at roughly 400 MB of heap for 1,000,000 rows. The
 * number of <em>distinct</em> members is usually in the low thousands, so interning collapses that by
 * two orders of magnitude and removes the dominant share of allocation pressure at the same time.
 *
 * <p>Interning is per read, not global: a dictionary scoped to one dataset cannot leak into another, and
 * a long-running service does not accumulate members from every file it has ever seen.
 */
final class MemberDictionary {

    private final Map<String, String>[] byDimension;

    @SuppressWarnings("unchecked")
    MemberDictionary(int dimensions) {
        this.byDimension = new Map[dimensions];
        for (int i = 0; i < dimensions; i++) {
            byDimension[i] = new HashMap<>();
        }
    }

    /** Returns the canonical instance of {@code member} for the given dimension position. */
    String intern(int position, String member) {
        Map<String, String> table = byDimension[position];
        String existing = table.get(member);
        if (existing != null) {
            return existing;
        }
        table.put(member, member);
        return member;
    }
}
