package io.allocation.kernel.exec;

import io.allocation.kernel.coord.Coord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The column representation of a driver table.
 *
 * <p>It replaced a {@code List<DriverRow>} for one reason — a million driver rows as objects cost about
 * 90MB, as columns about 28MB — so the tests are about proving the swap is invisible: same members, same
 * weights, same treatment of an absent dimension.
 */
class DriverTableTest {

    private static DriverTable tableOf(String[][] rows, long... weights) {
        DriverTable.Builder builder = DriverTable.builder(2);
        for (int i = 0; i < rows.length; i++) {
            builder.add(rows[i], weights[i]);
        }
        return builder.build();
    }

    @Test
    void answersExactlyWhatTheRowWouldHave() {
        DriverTable table = tableOf(new String[][]{{"P1", "S1"}, {"P1", "S2"}}, 10, 20);

        assertEquals(2, table.rowCount());
        assertEquals("P1", table.member(0, 0));
        assertEquals("S2", table.member(1, 1));
        assertEquals(10, table.weight(0));
        assertEquals(20, table.weight(1));
        assertEquals(new Coord(new String[]{"P1", "S1"}), table.coord(0));
        assertTrue(table.has(0, 1));
    }

    @Test
    void keepsAnAbsentDimensionAbsentRatherThanEmpty() {
        // A driver file without a plant column must not look like it had an empty plant: target mode
        // SOURCE asks whether the column was there at all.
        DriverTable table = tableOf(new String[][]{{null, "S1"}}, 5);

        assertFalse(table.has(0, 0), "an absent member must report as absent");
        assertNull(table.member(0, 0));
        assertEquals("", table.coord(0).memberOrEmpty(0), "the canonical key renders it as empty");
        assertTrue(table.coord(0).member(0) == null);
    }

    @Test
    void sharesMemberInstancesAcrossRows() {
        // The memory claim rests on this: a plant name repeated a million times exists once.
        DriverTable table = tableOf(new String[][]{{"P1", "S1"}, {"P1", "S2"}, {"P1", "S1"}}, 1, 2, 3);
        assertSame(table.member(0, 0), table.member(2, 0));
        assertSame(table.member(0, 1), table.member(2, 1));
        assertNotEquals(table.member(0, 1), table.member(1, 1));
    }

    @Test
    void growsPastItsInitialCapacity() {
        int rows = 5_000;
        DriverTable.Builder builder = DriverTable.builder(2);
        for (int i = 0; i < rows; i++) {
            builder.add(new String[]{"P" + (i % 13), "S" + i}, i);
        }
        DriverTable table = builder.build();

        assertEquals(rows, table.rowCount());
        assertEquals("S0", table.member(0, 1));
        assertEquals("S" + (rows - 1), table.member(rows - 1, 1));
        assertEquals(rows - 1, table.weight(rows - 1));
    }

    @Test
    void comparesCoordinatesForGroupingOnly() {
        DriverTable table = tableOf(new String[][]{{"P1", "S1"}, {"P1", "S1"}, {"P1", "S2"}}, 1, 2, 3);

        assertEquals(0, table.compareCoordinates(0, 1), "equal coordinates must compare equal");
        assertNotEquals(0, table.compareCoordinates(0, 2));
        // Equality is what duplicate detection needs; the order only has to be consistent, so the one
        // property worth pinning is antisymmetry.
        assertEquals(Integer.signum(table.compareCoordinates(0, 2)),
                -Integer.signum(table.compareCoordinates(2, 0)));
    }

    @Test
    void reportsAnEmptyTable() {
        DriverTable table = DriverTable.builder(2).build();
        assertEquals(0, table.rowCount());
    }

    @Test
    void rejectsARowWithTheWrongArity() {
        DriverTable.Builder builder = DriverTable.builder(2);
        assertThrows(IllegalArgumentException.class, () -> builder.add(new String[]{"P1"}, 1));
    }
}
