package io.allocation.kernel.exec;

import io.allocation.kernel.coord.Coord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Driver weights for one rule, stored as columns rather than as objects.
 *
 * <h2>Why not a {@code List<DriverRow>}</h2>
 * A driver table is the one thing allocation cannot stream: a pool needs random access to the weights of
 * the targets it may allocate to, so the whole table has to be resident. That makes its <em>representation</em>
 * the memory limit of a wide allocation — a million driver rows as objects cost roughly 90MB (record,
 * coordinate, member array, references) before any arithmetic happens.
 *
 * <p>Stored as columns, the same million rows cost {@code 4 bytes × dimensions + 8 bytes} each: 28MB for
 * five dimensions. Members are interned per dimension, so a plant name that appears a million times
 * exists once. Nothing here changes what is computed — every accessor answers exactly what the row object
 * answered — which is why the corpus still matches byte for byte after the switch.
 *
 * <h2>Absent members</h2>
 * A driver file need not carry every dimension, and "the column was absent" is materially different from
 * "the member is empty": target modes ask whether the dimension was there at all. An absent member is
 * therefore stored as {@code -1}, not as the empty string.
 */
final class DriverTable {

    private static final int ABSENT = -1;

    private final int arity;
    private final int[] ids;
    private final long[] weights;
    private final List<List<String>> dictionaries;
    private final List<Map<String, Integer>> memberIds;
    private final int rows;

    private DriverTable(int arity, int[] ids, long[] weights, List<List<String>> dictionaries,
                        List<Map<String, Integer>> memberIds, int rows) {
        this.arity = arity;
        this.ids = ids;
        this.weights = weights;
        this.dictionaries = dictionaries;
        this.memberIds = memberIds;
        this.rows = rows;
    }

    /** A builder sized for a table of unknown length. */
    static Builder builder(int dimensionCount) {
        return new Builder(dimensionCount);
    }

    int rowCount() {
        return rows;
    }

    /** True when the driver row carried this dimension at all. */
    boolean has(int row, int dimension) {
        return ids[row * arity + dimension] != ABSENT;
    }

    /** The member, or {@code null} when the dimension was absent. */
    String member(int row, int dimension) {
        int id = ids[row * arity + dimension];
        return id == ABSENT ? null : dictionaries.get(dimension).get(id);
    }

    long weight(int row) {
        return weights[row];
    }

    /** Builds the coordinate for one row. Allocates, so callers loop over {@link #member} where they can. */
    Coord coord(int row) {
        String[] members = new String[arity];
        for (int i = 0; i < arity; i++) {
            members[i] = member(row, i);
        }
        return new Coord(members);
    }

    /**
     * Compares two driver rows so that equal coordinates become adjacent when sorted.
     *
     * <p>The order itself is arbitrary — members are compared by dictionary id, not by text — but it is
     * consistent, and equality is exact because the per-dimension dictionaries are injective. That is all
     * duplicate detection needs, and it avoids building a key string for every driver row.
     */
    int compareCoordinates(int a, int b) {
        int offsetA = a * arity;
        int offsetB = b * arity;
        for (int i = 0; i < arity; i++) {
            int idA = ids[offsetA + i];
            int idB = ids[offsetB + i];
            if (idA == idB) {
                continue;
            }
            if (idA == ABSENT) {
                return idB == ABSENT ? 0 : -1;
            }
            if (idB == ABSENT) {
                return 1;
            }
            return Integer.compare(idA, idB);
        }
        return 0;
    }

    /** Collects rows, growing the columns geometrically. */
    static final class Builder {

        private final int arity;
        private final List<List<String>> dictionaries = new ArrayList<>();
        private final List<Map<String, Integer>> memberIds = new ArrayList<>();
        private int[] ids;
        private long[] weights;
        private int rows;

        private Builder(int dimensionCount) {
            if (dimensionCount < 1) {
                throw new IllegalArgumentException("a driver table needs at least one dimension");
            }
            this.arity = dimensionCount;
            for (int i = 0; i < dimensionCount; i++) {
                dictionaries.add(new ArrayList<>());
                memberIds.add(new HashMap<>());
            }
            this.ids = new int[dimensionCount * 1024];
            this.weights = new long[1024];
        }

        /**
         * Appends one row.
         *
         * @param members one entry per dimension, {@code null} when the column was absent
         * @param weight  scaled weight
         */
        Builder add(String[] members, long weight) {
            if (members.length != arity) {
                throw new IllegalArgumentException("expected " + arity + " members, got " + members.length);
            }
            if (rows == weights.length) {
                int capacity = weights.length + (weights.length >> 1) + 1;
                weights = java.util.Arrays.copyOf(weights, capacity);
                ids = java.util.Arrays.copyOf(ids, capacity * arity);
            }
            int offset = rows * arity;
            for (int i = 0; i < arity; i++) {
                ids[offset + i] = id(members[i], i);
            }
            weights[rows] = weight;
            rows++;
            return this;
        }

        private int id(String member, int dimension) {
            if (member == null) {
                return ABSENT;
            }
            Map<String, Integer> index = memberIds.get(dimension);
            Integer known = index.get(member);
            if (known != null) {
                return known;
            }
            int id = dictionaries.get(dimension).size();
            index.put(member, id);
            dictionaries.get(dimension).add(member);
            return id;
        }

        DriverTable build() {
            return new DriverTable(arity, ids, weights, dictionaries, memberIds, rows);
        }
    }
}
