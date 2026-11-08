package io.allocation.kernel.reconcile;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.coord.Coord;
import io.allocation.kernel.money.Decimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the disk-partitioned comparator.
 *
 * <p>The important test here is not any single scenario: it is that this implementation and
 * {@link Reconciler} agree. They are two independent mechanisms for the same claim, one in memory and one
 * on disk, and the moment they disagree one of them is lying about a financial number.
 *
 * <p>The second most important test is that the shard count cannot change the result. Partitioning is a
 * performance decision; if it ever influenced an outcome, the report would depend on how many files
 * happened to be open.
 */
class ShardedReconcilerTest {

    private static final List<String> DIMENSIONS = List.of("plant", "account");

    @TempDir
    Path directory;

    @Test
    void agreesWithTheInMemoryComparatorOnAGeneratedDataset() throws IOException {
        Map<String, Long> reference = new LinkedHashMap<>();
        Map<String, Long> actual = new LinkedHashMap<>();
        for (int plant = 0; plant < 7; plant++) {
            for (int account = 0; account < 11; account++) {
                String key = key("P" + plant, "A" + account);
                long value = (plant * 1_000L + account * 37L) % 977L;
                switch ((plant * 11 + account) % 5) {
                    case 0 -> { }                              // only in the reference: MISSING
                    case 1 -> {                                // only in the actual: EXTRA
                        actual.put(key, value);
                    }
                    case 2 -> {                                // differs by a cent
                        reference.put(key, value);
                        actual.put(key, value + 1);
                    }
                    case 3 -> {                                // identical
                        reference.put(key, value);
                        actual.put(key, value);
                    }
                    default -> {                               // differs by a lot
                        reference.put(key, value);
                        actual.put(key, value * 3);
                    }
                }
            }
        }

        Reconciler.Report inMemory = Reconciler.compare(reference, actual, Reconciler.Options.exact());
        ShardedReconciler.Summary sharded = ShardedReconciler.compare(
                AmountRows.of(actual), AmountRows.of(reference), ShardedReconciler.Options.of(DIMENSIONS, 2));

        assertEquals(inMemory.status(), sharded.status());
        assertEquals(inMemory.matched(), sharded.matched());
        assertEquals(inMemory.differed(), sharded.differed());
        assertEquals(inMemory.missing(), sharded.missing());
        assertEquals(inMemory.extra(), sharded.extra());
        assertEquals(Decimal.format(inMemory.expectedTotal(), 2), sharded.referenceTotal());
        assertEquals(Decimal.format(inMemory.actualTotal(), 2), sharded.actualTotal());
    }

    @Test
    void theShardCountCannotChangeTheResult() throws IOException {
        List<Map.Entry<String, Long>> actual = new ArrayList<>();
        List<Map.Entry<String, Long>> reference = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            String key = key("P" + (i % 17), "A" + (i % 23));
            reference.add(Map.entry(key, (long) i * 100));
            actual.add(Map.entry(key, i % 7 == 0 ? (long) i * 100 + 1 : (long) i * 100));
        }

        List<ShardedReconciler.Summary> summaries = new ArrayList<>();
        List<String> reports = new ArrayList<>();
        for (int shards : new int[]{1, 2, 7, 32, 64}) {
            Path target = directory.resolve("report-" + shards);
            summaries.add(ShardedReconciler.compare(AmountRows.of(actual), AmountRows.of(reference),
                    ShardedReconciler.Options.of(DIMENSIONS, 2).withShards(shards), target));
            reports.add(Files.readString(target.resolve("differences.csv"), StandardCharsets.UTF_8));
        }

        ShardedReconciler.Summary first = summaries.get(0);
        for (ShardedReconciler.Summary other : summaries) {
            assertEquals(first.matched(), other.matched());
            assertEquals(first.differed(), other.differed());
            assertEquals(first.top(), other.top());
        }
        for (String report : reports) {
            // Same rows, same order: partitioning must not decide reporting order.
            assertEquals(reports.get(0), report);
        }
    }

    @Test
    void reportsAnAmountDifferenceWithItsSign() throws IOException {
        ShardedReconciler.Summary summary = compare(
                rows(row("P1", "A1", 49_99L), row("P1", "A2", 100_00L)),
                rows(row("P1", "A1", 50_00L), row("P1", "A2", 100_00L)));

        assertEquals("DIFFERENCES", summary.status());
        assertEquals(1, summary.differed());
        assertEquals(1, summary.matched());
        ShardedReconciler.Difference difference = summary.top().get(0);
        assertEquals("DIFFERENT", difference.kind());
        assertEquals("plant=P1 | account=A1", difference.coordinate());
        assertEquals("50.00", difference.referenceAmount());
        assertEquals("49.99", difference.actualAmount());
        assertEquals("-0.01", difference.delta());
    }

    @Test
    void distinguishesMissingFromExtra() throws IOException {
        ShardedReconciler.Summary summary = compare(
                rows(row("P1", "A1", 10_00L), row("P1", "A9", 5_00L)),
                rows(row("P1", "A1", 10_00L), row("P1", "A2", 5_00L)));

        assertEquals(1, summary.missing(), "A2 is expected but absent from the result");
        assertEquals(1, summary.extra(), "A9 is in the result but nobody expected it");
        assertEquals(1, summary.matched());
        assertFalse(summary.matchedExactly());
    }

    @Test
    void toleranceAbsorbsADifferenceUpToAndIncludingItself() throws IOException {
        ShardedReconciler.Options options = ShardedReconciler.Options.of(DIMENSIONS, 2).withTolerance(1L);

        ShardedReconciler.Summary within = ShardedReconciler.compare(
                AmountRows.of(rows(row("P1", "A1", 100_01L))),
                AmountRows.of(rows(row("P1", "A1", 100_00L))), options);
        ShardedReconciler.Summary beyond = ShardedReconciler.compare(
                AmountRows.of(rows(row("P1", "A1", 100_02L))),
                AmountRows.of(rows(row("P1", "A1", 100_00L))), options);

        assertEquals(1, within.matched());
        assertEquals(0, within.differed());
        assertEquals(1, beyond.differed());
    }

    @Test
    void aggregatesDuplicateCoordinatesAndReportsHowMany() throws IOException {
        ShardedReconciler.Summary summary = compare(
                rows(row("P1", "A1", 30_00L), row("P1", "A1", 20_00L), row("P1", "A2", 7_00L)),
                rows(row("P1", "A1", 50_00L), row("P1", "A2", 7_00L)));

        assertEquals("MATCH", summary.status());
        assertEquals(2, summary.matched());
        assertEquals(1, summary.actualDuplicateRows());
        assertEquals(0, summary.referenceDuplicateRows());
    }

    @Test
    void refusesDuplicatesWhenTheCallerSaysTheDataShouldBeUnique() {
        ShardedReconciler.Options options = ShardedReconciler.Options.of(DIMENSIONS, 2)
                .withDuplicates(Reconciler.DuplicatePolicy.REJECT);

        AllocationException e = assertThrows(AllocationException.class, () -> ShardedReconciler.compare(
                AmountRows.of(rows(row("P1", "A1", 1L), row("P1", "A1", 2L))),
                AmountRows.of(rows(row("P1", "A1", 1L))), options));

        assertEquals(ErrorCode.RULE_INVALID, e.code());
        assertTrue(e.getMessage().contains("duplicate coordinate"), e.getMessage());
    }

    @Test
    void rejectsOnlyTheSideThatWasNamed() throws IOException {
        List<Map.Entry<String, Long>> duplicatedReference = rows(row("P1", "A1", 1L), row("P1", "A1", 2L));
        List<Map.Entry<String, Long>> singleActual = rows(row("P1", "A1", 3L));

        ShardedReconciler.Summary summary = ShardedReconciler.compare(AmountRows.of(singleActual),
                AmountRows.of(duplicatedReference), ShardedReconciler.Options.of(DIMENSIONS, 2)
                        .withDuplicates(Reconciler.DuplicatePolicy.REJECT_ACTUAL));

        assertEquals(1, summary.matched(), "the reference may repeat, the actual side may not");
        assertEquals(1, summary.referenceDuplicateRows());

        assertThrows(AllocationException.class, () -> ShardedReconciler.compare(AmountRows.of(singleActual),
                AmountRows.of(duplicatedReference), ShardedReconciler.Options.of(DIMENSIONS, 2)
                        .withDuplicates(Reconciler.DuplicatePolicy.REJECT_REFERENCE)));
    }

    @Test
    void breaksDownCountsByAccount() throws IOException {
        ShardedReconciler.Summary summary = compare(
                rows(row("P2", "A1", 1L), row("P2", "A2", 2L), row("P2", "A9", 3L)),
                rows(row("P1", "A1", 1L), row("P2", "A1", 1L), row("P2", "A2", 5L)));

        assertEquals(List.of("A1", "A2", "A9"),
                summary.accounts().stream().map(ShardedReconciler.Account::account).toList());
        ShardedReconciler.Account a1 = summary.accounts().get(0);
        assertEquals(1, a1.matched());
        assertEquals(1, a1.missing(), "P1/A1 is expected and absent");
        ShardedReconciler.Account a2 = summary.accounts().get(1);
        assertEquals(1, a2.differed());
        ShardedReconciler.Account a9 = summary.accounts().get(2);
        assertEquals(1, a9.extra());
    }

    @Test
    void keepsTheLargestDifferencesInMagnitudeOrder() throws IOException {
        ShardedReconciler.Summary summary = ShardedReconciler.compare(
                AmountRows.of(rows(row("P1", "A1", 0L), row("P1", "A2", 0L), row("P1", "A3", 0L))),
                AmountRows.of(rows(row("P1", "A1", 5_00L), row("P1", "A2", 900_00L), row("P1", "A3", 5_00L))),
                ShardedReconciler.Options.of(DIMENSIONS, 2).withTop(2));

        assertEquals(2, summary.top().size(), "top is a bound, not a total");
        assertEquals("plant=P1 | account=A2", summary.top().get(0).coordinate());
        assertEquals("-900.00", summary.top().get(0).delta());
        assertEquals("A1", summary.top().get(1).account(), "equal magnitudes order by coordinate");
    }

    @Test
    void publishesTheDifferenceFileAndRefusesToOverwriteOne() throws IOException {
        Path target = directory.resolve("report");

        ShardedReconciler.Summary summary = ShardedReconciler.compare(
                AmountRows.of(rows(row("P1", "A1", 49_99L))),
                AmountRows.of(rows(row("P1", "A1", 50_00L))),
                ShardedReconciler.Options.of(DIMENSIONS, 2), target);

        assertNotNull(summary.differences());
        assertEquals(target.resolve("differences.csv"), summary.differences());
        String csv = Files.readString(summary.differences(), StandardCharsets.UTF_8);
        assertEquals("kind,account,coordinate,reference_amount,actual_amount,difference\n"
                + "DIFFERENT,A1,plant=P1 | account=A1,50.00,49.99,-0.01\n", csv);

        AllocationException e = assertThrows(AllocationException.class, () -> ShardedReconciler.compare(
                AmountRows.of(rows(row("P1", "A1", 1L))), AmountRows.of(rows(row("P1", "A1", 1L))),
                ShardedReconciler.Options.of(DIMENSIONS, 2), target));
        assertEquals(ErrorCode.RULE_INVALID, e.code());
    }

    @Test
    void aComparisonWithoutPublishingLeavesNoReportBehind() throws IOException {
        ShardedReconciler.Summary summary = ShardedReconciler.compare(
                AmountRows.of(rows(row("P1", "A1", 1L))), AmountRows.of(rows(row("P1", "A1", 1L))),
                ShardedReconciler.Options.of(DIMENSIONS, 2));

        assertEquals("MATCH", summary.status());
        assertEquals(null, summary.differences());
    }

    @Test
    void readsAFileInAnyColumnOrderWithAValueColumn() throws IOException {
        Path file = directory.resolve("reference.csv");
        Files.writeString(file, "\ufeffrow_id,account,value,plant,terminal_stage\n"
                + "r1,A1,10.00,P1,st9\n"
                + "r2,A2,1000.00,P1,st9\n", StandardCharsets.UTF_8);

        CsvAmountRows rows = new CsvAmountRows(file, DIMENSIONS, 2);
        List<Map.Entry<String, Long>> loaded = rows.load();

        assertEquals(Map.entry(key("P1", "A1"), 10_00L), loaded.get(0));
        assertEquals(Map.entry(key("P1", "A2"), 100_000L), loaded.get(1));
        assertNotNull(rows.digest(), "a file source must be able to name the bytes it compared");
        assertEquals(64, rows.digest().length());
    }

    @Test
    void refusesARowWithAnEmptyCoordinate() throws IOException {
        Path file = directory.resolve("bad.csv");
        Files.writeString(file, "plant,account,amount\nP1,,10.00\n", StandardCharsets.UTF_8);

        AllocationException e = assertThrows(AllocationException.class,
                () -> new CsvAmountRows(file, DIMENSIONS, 2).load());

        assertEquals(ErrorCode.AMOUNT_OUT_OF_RANGE, e.code());
        assertTrue(e.getMessage().contains("empty coordinate account"), e.getMessage());
    }

    @Test
    void refusesAFileWithoutExactlyOneAmountColumn() throws IOException {
        Path twoAmounts = directory.resolve("two.csv");
        Files.writeString(twoAmounts, "plant,account,amount,value\nP1,A1,1,1\n", StandardCharsets.UTF_8);
        Path none = directory.resolve("none.csv");
        Files.writeString(none, "plant,account,total\nP1,A1,1\n", StandardCharsets.UTF_8);

        assertEquals(ErrorCode.AMOUNT_OUT_OF_RANGE, assertThrows(AllocationException.class,
                () -> new CsvAmountRows(twoAmounts, DIMENSIONS, 2).load()).code());
        assertEquals(ErrorCode.AMOUNT_OUT_OF_RANGE, assertThrows(AllocationException.class,
                () -> new CsvAmountRows(none, DIMENSIONS, 2).load()).code());
    }

    @Test
    void formatsTotalsBeyondTheRangeOfALong() {
        // One more than Long.MAX_VALUE: a total this size is exactly why the running sums are BigInteger.
        BigInteger beyond = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);

        assertEquals("92233720368547758.08", ShardedReconciler.format(beyond, 2));
        assertEquals("0.05", ShardedReconciler.format(BigInteger.valueOf(5), 2));
        assertEquals("-0.05", ShardedReconciler.format(BigInteger.valueOf(-5), 2));
        assertEquals("7", ShardedReconciler.format(BigInteger.valueOf(7), 0));
        assertEquals("123.00", ShardedReconciler.format(BigInteger.valueOf(123_00), 2));
    }

    @Test
    void anEmptyInputOnBothSidesIsAMatch() throws IOException {
        ShardedReconciler.Summary summary = ShardedReconciler.compare(
                AmountRows.of(List.of()), AmountRows.of(List.of()),
                ShardedReconciler.Options.of(DIMENSIONS, 2));

        assertEquals("MATCH", summary.status());
        assertEquals(0, summary.actualRows());
        assertEquals("0.00", summary.actualTotal());
        assertFalse(summary.top().iterator().hasNext());
    }

    // ---------------------------------------------------------------- helpers

    private ShardedReconciler.Summary compare(List<Map.Entry<String, Long>> actual,
                                              List<Map.Entry<String, Long>> reference) throws IOException {
        return ShardedReconciler.compare(AmountRows.of(actual), AmountRows.of(reference),
                ShardedReconciler.Options.of(DIMENSIONS, 2));
    }

    @SafeVarargs
    private static List<Map.Entry<String, Long>> rows(Map.Entry<String, Long>... rows) {
        return List.of(rows);
    }

    private static Map.Entry<String, Long> row(String plant, String account, long amount) {
        return Map.entry(key(plant, account), amount);
    }

    private static String key(String plant, String account) {
        return new Coord(new String[]{plant, account}).key();
    }
}
