package io.allocation.kernel.exec;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.coord.Coord;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the buffer that makes peak heap independent of input size.
 *
 * <p>Everything here is about one property: a store must behave exactly like the {@code List<Row>} it
 * replaced, whether or not it spilled. The interesting cases are the ones a naive encoder loses —
 * absent members, identifiers that are not ASCII, rows that arrive one per spill batch.
 */
class RowStoreTest {

    private static Row row(String plant, String account, long amount, String id) {
        return new Row(new Coord(new String[]{plant, account}), amount, new String[]{id});
    }

    private static List<Row> collect(RowStore store) {
        List<Row> rows = new ArrayList<>();
        store.forEach(rows::add);
        return rows;
    }

    @Test
    void keepsInsertionOrderWithoutSpilling() {
        try (SpillSpace spill = new SpillSpace();
             RowStore store = new RowStore(spill, 100)) {
            store.add(row("P2", "A", 10, "b"));
            store.add(row("P1", "A", 20, "a"));
            store.add(row("P3", "B", 30, "c"));
            assertFalse(store.spilled(), "three rows must not touch the disk");
            assertFalse(spill.used(), "no spill directory may be created for an in-memory run");

            List<Row> rows = collect(store);
            assertEquals(3, rows.size());
            assertEquals("b", rows.get(0).firstId());
            assertEquals("a", rows.get(1).firstId());
            assertEquals(60, store.total());
        }
    }

    @Test
    void keepsInsertionOrderAcrossTheSpillBoundary() {
        try (SpillSpace spill = new SpillSpace();
             RowStore store = new RowStore(spill, 2)) {
            for (int i = 0; i < 10; i++) {
                store.add(row("P" + i, "A", i, "id-" + i));
            }
            assertTrue(store.spilled(), "ten rows against a budget of two must spill");
            assertTrue(Files.exists(store.spillFile()), "the spill file must exist while the store is open");

            List<Row> rows = collect(store);
            assertEquals(10, rows.size());
            for (int i = 0; i < 10; i++) {
                assertEquals("id-" + i, rows.get(i).firstId(), "row " + i + " moved");
                assertEquals(i, rows.get(i).amount());
                assertEquals("P" + i, rows.get(i).coord().member(0));
            }
            assertEquals(45, store.total());
        }
    }

    @Test
    void canBeReplayedAsManyTimesAsTheCallerNeeds() {
        try (SpillSpace spill = new SpillSpace();
             RowStore store = new RowStore(spill, 1)) {
            store.add(row("P1", "A", 10, "a"));
            store.add(row("P1", "B", 20, "b"));

            assertEquals(2, collect(store).size());
            assertEquals(2, collect(store).size());
            assertEquals(2, collect(store).size());
        }
    }

    @Test
    void survivesAbsentMembersAndAwkwardText() {
        String awkward = "quote\"comma,newline\n日𝄞";
        Row absent = new Row(new Coord(new String[]{null, "A"}), 5, new String[]{awkward});
        Row empty = new Row(new Coord(new String[]{"", "B"}), 7, new String[]{"", "second"});
        try (SpillSpace spill = new SpillSpace();
             RowStore store = new RowStore(spill, 1)) {
            store.add(absent);
            store.add(empty);

            List<Row> rows = collect(store);
            // A null member is "the dimension was absent", which is not the same as an empty member.
            assertTrue(rows.get(0).coord().member(0) == null, "an absent member must stay absent");
            assertEquals(awkward, rows.get(0).firstId());
            assertEquals("", rows.get(1).coord().member(0));
            assertEquals("second", rows.get(1).ids()[1]);
            assertNotEquals(rows.get(0).coord().key(), rows.get(1).coord().key());
        }
    }

    @Test
    void deletesTheSpillFileWhenClosed() {
        SpillSpace spill = new SpillSpace();
        RowStore store = new RowStore(spill, 1);
        store.add(row("P1", "A", 1, "a"));
        store.add(row("P1", "A", 2, "b"));
        Path file = store.spillFile();
        assertTrue(Files.exists(file));

        store.close();
        assertFalse(Files.exists(file), "closing the store must remove its spill file");
        spill.close();
    }

    @Test
    void reportsOverflowRatherThanWrappingTheTotal() {
        try (SpillSpace spill = new SpillSpace();
             RowStore store = new RowStore(spill, 1)) {
            store.add(row("P1", "A", Long.MAX_VALUE, "a"));
            AllocationException failure = assertThrows(AllocationException.class,
                    () -> store.add(row("P1", "A", 1, "b")));
            assertEquals(ErrorCode.AMOUNT_OUT_OF_RANGE, failure.code());
        }
    }

    @Test
    void refusesWorkAfterClose() {
        SpillSpace spill = new SpillSpace();
        RowStore store = new RowStore(spill, 10);
        store.close();
        assertThrows(IllegalStateException.class, () -> store.add(row("P1", "A", 1, "a")));
        assertThrows(IllegalStateException.class, () -> store.forEach(row -> { }));
        spill.close();
    }

    @Test
    void rejectsABudgetOfZero() {
        try (SpillSpace spill = new SpillSpace()) {
            assertThrows(IllegalArgumentException.class, () -> new RowStore(spill, 0));
        }
    }
}
