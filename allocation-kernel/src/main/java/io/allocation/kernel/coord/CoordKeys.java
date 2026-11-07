package io.allocation.kernel.coord;

import io.allocation.kernel.json.Json;

/** Builds canonical keys over a subset of a coordinate's dimensions. */
public final class CoordKeys {

    private CoordKeys() {
    }

    /**
     * Key over the given dimension positions, in the given order.
     *
     * <p>Used to bucket driver rows by the dimensions that a rule inherits from its pool. An absent
     * member renders as an empty string, exactly as the reference implementation's map lookup does.
     */
    public static String at(Coord coord, int[] positions) {
        if (positions.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(12 + positions.length * 12);
        sb.append('[');
        for (int i = 0; i < positions.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            Json.writeString(sb, coord.memberOrEmpty(positions[i]));
        }
        return sb.append(']').toString();
    }
}
