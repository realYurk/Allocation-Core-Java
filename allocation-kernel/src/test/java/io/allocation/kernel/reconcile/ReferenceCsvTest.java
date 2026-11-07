package io.allocation.kernel.reconcile;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.coord.Coord;
import io.allocation.kernel.spec.AllocationSpec;
import io.allocation.kernel.spec.SpecCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what the reference reader accepts.
 *
 * <p>The list of tolerated quirks is short and deliberate, and everything outside it fails loudly.
 * A reconciler that silently ignores a column, merges a duplicate or rounds a badly scaled amount is
 * not evidence — it is a second opinion that agrees with whatever it was given.
 */
class ReferenceCsvTest {

    @TempDir
    Path directory;

    private static String key(String... members) {
        return new Coord(members).key();
    }

    private Path write(String name, String content) {
        Path path = directory.resolve(name);
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return path;
    }

    private static Map<String, Long> asMap(ReferenceCsv.Table table) {
        return Reconciler.index(table.rows(), Reconciler.DuplicatePolicy.AGGREGATE);
    }

    private static AllocationSpec spec(int precision, String... dimensions) {
        return new AllocationSpec(AllocationSpec.SCHEMA, AllocationSpec.ROUNDING_LARGEST_REMAINDER_IDENTITY,
                "reference test", precision, List.of(dimensions), List.of(), Map.of(), Map.of(), Map.of(), List.of());
    }

    @Test
    void derivesAndSortsDimensionsFromTheHeader() {
        Path path = write("a.csv", "sku,plant,account,amount\nS1,P1,A1,12.34\nS2,P2,A2,-5.00\n");

        ReferenceCsv.Table table = ReferenceCsv.load(path, 2);

        assertEquals(List.of("account", "plant", "sku"), table.dimensions());
        assertEquals(2, table.rowCount());
        Map<String, Long> amounts = asMap(table);
        assertEquals(1_234L, amounts.get(key("A1", "P1", "S1")));
        assertEquals(-500L, amounts.get(key("A2", "P2", "S2")));
    }

    @Test
    void columnOrderAndLineEndingsDoNotChangeTheResult() {
        ReferenceCsv.Table first = ReferenceCsv.load(
                write("first.csv", "plant,account,amount\nP1,A1,10.00\n"), 2);
        ReferenceCsv.Table second = ReferenceCsv.load(
                write("second.csv", "amount,account,plant\r\n10.00,A1,P1\r\n"), 2);

        assertEquals(first.dimensions(), second.dimensions());
        assertEquals(asMap(first), asMap(second));
    }

    @Test
    void ignoresProvenanceColumns() {
        Path path = write("results.csv",
                "terminal_stage,row_id,plant,account,amount\nst1,r-1,P1,A1,10.00\nst1,r-2,P2,A2,20.00\n");

        ReferenceCsv.Table table = ReferenceCsv.load(path, 2);

        assertEquals(List.of("account", "plant"), table.dimensions());
        assertEquals(Map.of(key("A1", "P1"), 1_000L, key("A2", "P2"), 2_000L), asMap(table));
    }

    @Test
    void acceptsValueAsTheAmountColumn() {
        ReferenceCsv.Table table = ReferenceCsv.load(
                write("value.csv", "plant,account,value\nP1,A1,10\n"), 0);

        assertEquals(Map.of(key("A1", "P1"), 10L), asMap(table));
    }

    @Test
    void stripsTheByteOrderMark() {
        ReferenceCsv.Table table = ReferenceCsv.load(
                write("bom.csv", "\uFEFFplant,account,amount\nP1,A1,1.00\n"), 2);

        assertEquals(List.of("account", "plant"), table.dimensions());
        assertEquals(100L, asMap(table).get(key("A1", "P1")));
    }

    @Test
    void keepsDuplicateRowsForTheCallerToDecideOn() {
        Path path = write("duplicates.csv", "plant,account,amount\nP1,A1,1.00\nP1,A1,2.00\n");

        ReferenceCsv.Table table = ReferenceCsv.load(path, 2);

        assertEquals(2, table.rowCount());
        assertEquals(2, table.rows().size());
        assertEquals(300L, asMap(table).get(key("A1", "P1")));
    }

    @Test
    void reportsTheDigestOfTheRawBytes() {
        Path path = write("digest.csv", "plant,account,amount\nP1,A1,1.00\n");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        assertEquals(SpecCodec.sha256Hex(bytes), ReferenceCsv.load(path, 2).sha256());
    }

    @Test
    void rejectsMoreThanOneAmountColumn() {
        assertMessage("more than one amount column",
                () -> ReferenceCsv.load(write("two.csv", "plant,amount,value\nP1,1,1\n"), 2));
    }

    @Test
    void rejectsAMissingAmountColumn() {
        assertMessage("no amount or value column",
                () -> ReferenceCsv.load(write("none.csv", "plant,account\nP1,A1\n"), 2));
    }

    @Test
    void rejectsDuplicateColumns() {
        assertMessage("duplicate column",
                () -> ReferenceCsv.load(write("dup.csv", "plant,plant,amount\nP1,P1,1\n"), 2));
    }

    @Test
    void rejectsAnEmptyHeader() {
        assertMessage("empty header",
                () -> ReferenceCsv.load(write("empty.csv", "plant,,amount\nP1,,1\n"), 2));
    }

    @Test
    void rejectsAShortRow() {
        assertMessage("field(s), header has",
                () -> ReferenceCsv.load(write("short.csv", "plant,account,amount\nP1,A1\n"), 2));
    }

    @Test
    void rejectsAnEmptyCoordinate() {
        assertMessage("empty coordinate account",
                () -> ReferenceCsv.load(write("blank.csv", "plant,account,amount\nP1, ,1\n"), 2));
    }

    @Test
    void rejectsACoordinateThatIsNotAStringInAKey() {
        assertMessage("is not a string", () -> Reconciler.render("[1,2]", List.of("a", "b")));
    }

    @Test
    void rejectsAnAmountWithMoreScaleThanTheRuleAllows() {
        AllocationException e = assertThrows(AllocationException.class,
                () -> ReferenceCsv.load(write("scale.csv", "plant,account,amount\nP1,A1,1.005\n"), 2));
        assertEquals("ALLOC_002", e.code().code());
    }

    @Test
    void usesTheRuleDimensionOrderWhenSpecIsSupplied() {
        // The rule's order is what the engine's own coordinate keys use, so it must not be re-sorted.
        AllocationSpec rule = spec(2, "sku", "plant");
        ReferenceCsv.Table table = ReferenceCsv.load(
                write("rule.csv", "plant,sku,amount\nP1,S1,3.00\n"), rule);

        assertEquals(List.of("sku", "plant"), table.dimensions());
        assertEquals(300L, asMap(table).get(key("S1", "P1")));
    }

    @Test
    void rejectsAFileThatLacksADimensionTheRuleNeeds() {
        AllocationSpec rule = spec(2, "sku", "plant", "account");

        assertMessage("missing dimension account",
                () -> ReferenceCsv.load(write("partial.csv", "plant,sku,amount\nP1,S1,1\n"), rule));
    }

    @Test
    void commonDimensionsRejectsDifferentDimensionSets() {
        ReferenceCsv.Table left = ReferenceCsv.load(write("l.csv", "plant,account,amount\nP1,A1,1\n"), 2);
        ReferenceCsv.Table right = ReferenceCsv.load(write("r.csv", "plant,sku,amount\nP1,S1,1\n"), 2);

        assertThrows(AllocationException.class, () -> ReferenceCsv.commonDimensions(left, right));
    }

    @Test
    void commonDimensionsSortsAndMatches() {
        ReferenceCsv.Table left = ReferenceCsv.load(write("l.csv", "plant,account,amount\nP1,A1,1\n"), 2);
        ReferenceCsv.Table right = ReferenceCsv.load(write("r.csv", "account,plant,value\nA1,P1,1\n"), 2);

        assertEquals(List.of("account", "plant"), ReferenceCsv.commonDimensions(left, right));
    }

    @Test
    void twoExportsOfTheSameDataReconcileCleanly() {
        ReferenceCsv.Table first = ReferenceCsv.load(
                write("ours.csv", "terminal_stage,row_id,plant,account,amount\n"
                        + "st1,r1,P1,A1,10.00\nst1,r2,P1,A2,5.00\n"), 2);
        ReferenceCsv.Table second = ReferenceCsv.load(
                write("theirs.csv", "\uFEFFaccount,plant,value\nA1,P1,10.00\nA2,P1,5.00\n"), 2);

        List<String> dimensions = ReferenceCsv.commonDimensions(first, second);
        Reconciler.Report report = Reconciler.compare(
                Reconciler.index(second.rows(), Reconciler.DuplicatePolicy.AGGREGATE),
                Reconciler.index(first.rows(), Reconciler.DuplicatePolicy.AGGREGATE),
                Reconciler.Options.exact());

        assertEquals("MATCH", report.status(), dimensions.toString());
        assertEquals(2, report.matched());
        assertEquals(1_500L, report.actualTotal());
    }

    @Test
    void anExtraRowInOneExportIsStructural() {
        ReferenceCsv.Table ours = ReferenceCsv.load(
                write("ours.csv", "plant,account,amount\nP1,A1,10.00\n"), 2);
        ReferenceCsv.Table theirs = ReferenceCsv.load(
                write("theirs.csv", "plant,account,amount\nP1,A1,10.00\nP1,A2,0.01\n"), 2);

        Reconciler.Report report = Reconciler.compare(
                Reconciler.index(theirs.rows(), Reconciler.DuplicatePolicy.AGGREGATE),
                Reconciler.index(ours.rows(), Reconciler.DuplicatePolicy.AGGREGATE),
                Reconciler.Options.exact());

        assertEquals("DIFFERENCES", report.status());
        assertEquals(1, report.missing());
        assertEquals(-1L, Reconciler.top(Reconciler.describe(report, List.of("plant", "account")), 1)
                .get(0).delta());
    }

    private static void assertMessage(String expectedFragment, org.junit.jupiter.api.function.Executable body) {
        AllocationException e = assertThrows(AllocationException.class, body);
        assertTrue(e.getMessage().contains(expectedFragment),
                "expected message to contain \"" + expectedFragment + "\" but was: " + e.getMessage());
    }

    /** A cent is one unit at precision two; the scale comes from the caller, never from the text. */
    @Test
    void amountsAreScaledByTheGivenPrecision() {
        Map<String, Long> a = new LinkedHashMap<>();
        a.put(key("A1", "P1"), 1L);
        assertEquals(a, asMap(ReferenceCsv.load(write("m.csv", "plant,account,amount\nP1,A1,0.01\n"), 2)));
    }
}
