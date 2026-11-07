package io.allocation.kernel.coord;

import io.allocation.kernel.json.Json;

import java.util.List;

/**
 * A business coordinate: one member per declared dimension, in declaration order.
 *
 * <p>A member may be {@code null}, meaning "this dimension was not present in the source". That is a
 * different thing from an empty member, and the difference is financially material: a driver file
 * without a {@code plant} column must not be treated as having an empty plant, because the rule
 * decision for {@code SOURCE} targets asks whether the column was there at all. The canonical key
 * renders an absent member as an empty string, which is what the reference implementation does.
 *
 * <p>The key is a JSON array of the members, escaped exactly the way the reference encoder escapes
 * them. That single decision buys three things at once: a stable grouping key, a stable sort key, and a
 * stable input to row identifiers. It also means a member containing a comma, a quote or a newline
 * cannot collide with a different coordinate.
 */
public final class Coord implements Comparable<Coord> {

    private final String[] members;
    /**
     * Lazily built canonical key.
     *
     * <p>Not {@code final} and not {@code volatile}: the computation is idempotent, so a race can only
     * cause the same string to be built twice, never an inconsistent value. Driver rows in a
     * million-row file never need their key, and building one eagerly for each of them costs about
     * 100 MB for nothing.
     */
    private String key;

    public Coord(String[] members) {
        this.members = members;
    }

    public static Coord of(List<String> dimensions, String[] valuesByPosition) {
        if (valuesByPosition.length != dimensions.size()) {
            throw new IllegalArgumentException("coordinate arity mismatch: expected "
                    + dimensions.size() + ", got " + valuesByPosition.length);
        }
        return new Coord(valuesByPosition.clone());
    }

    private static String buildKey(String[] members) {
        StringBuilder sb = new StringBuilder(16 + members.length * 12);
        sb.append('[');
        for (int i = 0; i < members.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            Json.writeString(sb, members[i] == null ? "" : members[i]);
        }
        return sb.append(']').toString();
    }

    /** Member at the given dimension position, or {@code null} when absent from the source. */
    public String member(int position) {
        return members[position];
    }

    /** Member at the given position, with absence rendered as the empty string. */
    public String memberOrEmpty(int position) {
        String value = members[position];
        return value == null ? "" : value;
    }

    /** True when the source actually carried this dimension. */
    public boolean has(int position) {
        return members[position] != null;
    }

    public int size() {
        return members.length;
    }

    public String[] members() {
        return members.clone();
    }

    public String key() {
        String cached = key;
        if (cached == null) {
            cached = buildKey(members);
            key = cached;
        }
        return cached;
    }

    /** Compares by canonical key in UTF-8 byte order, which is the reference ordering. */
    @Override
    public int compareTo(Coord other) {
        return Utf8Order.compare(key(), other.key());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Coord other && key().equals(other.key());
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }

    @Override
    public String toString() {
        return key();
    }
}
