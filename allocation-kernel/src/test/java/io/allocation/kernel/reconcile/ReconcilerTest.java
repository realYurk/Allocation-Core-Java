package io.allocation.kernel.reconcile;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.coord.Coord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the behaviour of coordinate-level comparison.
 *
 * <p>These tests are deliberately about classification, not about arithmetic: the amounts used are
 * already-scaled longs, and what is being asserted is which bucket a coordinate lands in. Getting that
 * wrong is the failure mode that matters — a reconciler that quietly reports MATCH is worse than no
 * reconciler at all.
 */
class ReconcilerTest {

    private static final List<String> DIMENSIONS = List.of("plant", "account");

    private static String key(String... members) {
        return new Coord(members).key();
    }

    private static Map<String, Long> amounts(Object... pairs) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
        }
        return out;
    }

    @Nested
    @DisplayName("classification")
    class Classification {

        @Test
        void identicalSidesMatch() {
            Map<String, Long> side = amounts(key("P1", "A1"), 10_000L, key("P2", "A2"), 25_000L);

            Reconciler.Report report = Reconciler.compare(side, side, Reconciler.Options.exact());

            assertEquals("MATCH", report.status());
            assertTrue(report.matchedExactly());
            assertEquals(2, report.matched());
            assertEquals(0, report.differed());
            assertEquals(35_000L, report.expectedTotal());
            assertEquals(35_000L, report.actualTotal());
            assertTrue(report.differences().isEmpty());
        }

        @Test
        void differingAmountIsReportedWithSignedDelta() {
            Reconciler.Report report = Reconciler.compare(
                    amounts(key("P1", "A1"), 10_000L),
                    amounts(key("P1", "A1"), 9_900L),
                    Reconciler.Options.exact());

            assertEquals("DIFFERENCES", report.status());
            assertEquals(1, report.differed());
            assertFalse(report.hasStructuralDifference());
            Reconciler.Difference difference = report.differences().get(0);
            assertEquals(Reconciler.Kind.DIFFERENT, difference.kind());
            assertEquals(10_000L, difference.expected());
            assertEquals(9_900L, difference.actual());
            assertEquals(-100L, difference.delta());
        }

        @Test
        void missingAndExtraAreStructural() {
            Reconciler.Report report = Reconciler.compare(
                    amounts(key("P1", "A1"), 10_000L),
                    amounts(key("P2", "A2"), 10_000L),
                    Reconciler.Options.exact());

            assertEquals("DIFFERENCES", report.status());
            assertEquals(1, report.missing());
            assertEquals(1, report.extra());
            assertTrue(report.hasStructuralDifference());
            // Equal totals, different partners: exactly the case a total-only check would miss.
            assertEquals(report.expectedTotal(), report.actualTotal());
            assertEquals(2, report.differences().size());
        }

        @Test
        void extraDeltaIsTheActualAmountAndMissingDeltaIsItsNegation() {
            Reconciler.Report report = Reconciler.compare(
                    amounts(key("P1", "A1"), -7_000L),
                    amounts(key("P2", "A2"), 7_000L),
                    Reconciler.Options.exact());

            Map<Reconciler.Kind, Reconciler.Difference> byKind = new LinkedHashMap<>();
            for (Reconciler.Difference difference : report.differences()) {
                byKind.put(difference.kind(), difference);
            }
            assertEquals(7_000L, byKind.get(Reconciler.Kind.EXTRA).delta());
            assertEquals(7_000L, byKind.get(Reconciler.Kind.MISSING).delta());
        }

        @Test
        void emptySidesMatch() {
            Reconciler.Report report = Reconciler.compare(Map.of(), Map.of(), Reconciler.Options.exact());

            assertEquals("MATCH", report.status());
            assertEquals(0L, report.expectedTotal());
            assertEquals(0L, report.actualTotal());
        }
    }

    @Nested
    @DisplayName("tolerance")
    class Tolerance {

        @Test
        void absorbsDifferencesUpToAndIncludingTheBoundary() {
            Reconciler.Report report = Reconciler.compare(
                    amounts(key("P1", "A1"), 10_000L),
                    amounts(key("P1", "A1"), 10_001L),
                    Reconciler.Options.within(1L));

            assertEquals("MATCH", report.status());
            assertEquals(1, report.matched());
        }

        @Test
        void toleranceIsInclusiveOnTheNegativeSideToo() {
            Reconciler.Report report = Reconciler.compare(
                    amounts(key("P1", "A1"), 10_000L),
                    amounts(key("P1", "A1"), 9_999L),
                    Reconciler.Options.within(1L));

            assertEquals("MATCH", report.status());
        }

        @Test
        void reportsBeyondTolerance() {
            Reconciler.Report report = Reconciler.compare(
                    amounts(key("P1", "A1"), 10_000L),
                    amounts(key("P1", "A1"), 10_002L),
                    Reconciler.Options.within(1L));

            assertEquals("DIFFERENCES", report.status());
            assertEquals(2L, report.differences().get(0).delta());
        }

        @Test
        void negativeToleranceIsRejected() {
            assertThrows(AllocationException.class, () -> new Reconciler.Options(-1L, Reconciler.DuplicatePolicy.AGGREGATE));
        }
    }

    @Nested
    @DisplayName("ordering")
    class Ordering {

        @Test
        void iteratesInUtf8ByteOrderNotUtf16Order() {
            // U+FFFD encodes as EF BF BD, U+1F600 as F0 9F 98 80, so U+FFFD sorts first in UTF-8.
            // UTF-16 compares the lone units 0xFFFD > 0xD83D and would order them the other way round.
            String replacement = key("\uFFFD");
            String emoji = key("\uD83D\uDE00");

            Reconciler.Report report = Reconciler.compare(
                    amounts(replacement, 1L, emoji, 1L),
                    Map.of(),
                    Reconciler.Options.exact());

            assertEquals(replacement, report.differences().get(0).key());
            assertEquals(emoji, report.differences().get(1).key());
        }

        @Test
        void differencesFollowCoordinateOrder() {
            Reconciler.Report report = Reconciler.compare(
                    amounts(key("P2", "A1"), 1L, key("P1", "A9"), 1L, key("P1", "A1"), 1L),
                    Map.of(),
                    Reconciler.Options.exact());

            List<String> order = new ArrayList<>();
            for (Reconciler.Difference difference : report.differences()) {
                order.add(difference.key());
            }
            assertEquals(List.of(key("P1", "A1"), key("P1", "A9"), key("P2", "A1")), order);
        }
    }

    @Nested
    @DisplayName("indexing")
    class Indexing {

        @Test
        void aggregatesDuplicateCoordinates() {
            List<Map.Entry<String, Long>> lines = List.of(
                    new java.util.AbstractMap.SimpleImmutableEntry<>(key("P1", "A1"), 1_000L),
                    new java.util.AbstractMap.SimpleImmutableEntry<>(key("P1", "A1"), 2_500L));

            Map<String, Long> index = Reconciler.index(lines, Reconciler.DuplicatePolicy.AGGREGATE);

            assertEquals(1, index.size());
            assertEquals(3_500L, index.get(key("P1", "A1")));
        }

        @Test
        void rejectsDuplicatesWhenAsked() {
            List<Map.Entry<String, Long>> lines = List.of(
                    new java.util.AbstractMap.SimpleImmutableEntry<>(key("P1", "A1"), 1_000L),
                    new java.util.AbstractMap.SimpleImmutableEntry<>(key("P1", "A1"), 2_500L));

            for (Reconciler.DuplicatePolicy policy : List.of(
                    Reconciler.DuplicatePolicy.REJECT,
                    Reconciler.DuplicatePolicy.REJECT_ACTUAL,
                    Reconciler.DuplicatePolicy.REJECT_REFERENCE)) {
                AllocationException e = assertThrows(AllocationException.class,
                        () -> Reconciler.index(lines, policy));
                assertTrue(e.getMessage().contains("duplicate coordinate"), e.getMessage());
            }
        }

        @Test
        void preservesFirstSeenOrder() {
            List<Map.Entry<String, Long>> lines = List.of(
                    new java.util.AbstractMap.SimpleImmutableEntry<>(key("P2", "A1"), 1L),
                    new java.util.AbstractMap.SimpleImmutableEntry<>(key("P1", "A1"), 2L));

            assertEquals(List.of(key("P2", "A1"), key("P1", "A1")),
                    new ArrayList<>(Reconciler.index(lines, Reconciler.DuplicatePolicy.AGGREGATE).keySet()));
        }
    }

    @Nested
    @DisplayName("presentation")
    class Presentation {

        private Reconciler.Report sample() {
            return Reconciler.compare(
                    amounts(key("P1", "A1"), 10_000L,
                            key("P1", "A2"), 20_000L,
                            key("P2", "A2"), 5_000L,
                            key("P2", "A3"), 1_000L),
                    amounts(key("P1", "A1"), 10_000L,
                            key("P1", "A2"), 19_000L,
                            key("P3", "A3"), 1_000L),
                    Reconciler.Options.exact());
        }

        @Test
        void describeAddsAccountAndHumanCoordinate() {
            Reconciler.Report described = Reconciler.describe(sample(), DIMENSIONS);

            Reconciler.Difference first = described.differences().get(0);
            assertEquals("A2", first.account());
            assertEquals("plant=P1 | account=A2", first.coordinate());
        }

        @Test
        void accountsGroupCountsByAccountMember() {
            Reconciler.Report described = Reconciler.describe(sample(), DIMENSIONS);

            List<Reconciler.Account> accounts = Reconciler.accounts(described);

            assertEquals(List.of("A2", "A3"), accounts.stream().map(Reconciler.Account::account).toList());
            assertEquals(1, accounts.get(0).differed());
            assertEquals(1, accounts.get(0).missing());
            assertEquals(1, accounts.get(1).missing());
            assertEquals(1, accounts.get(1).extra());
            assertEquals(4, accounts.stream().mapToLong(Reconciler.Account::total).sum());
        }

        @Test
        void accountsFallBackToAllWithoutAnAccountDimension() {
            Reconciler.Report described = Reconciler.describe(sample(), List.of("plant", "region"));

            List<Reconciler.Account> accounts = Reconciler.accounts(described);

            assertEquals(1, accounts.size());
            assertEquals("ALL", accounts.get(0).account());
            assertEquals(4, accounts.get(0).total());
        }

        @Test
        void topOrdersByAbsoluteDeltaThenCoordinate() {
            Reconciler.Report described = Reconciler.describe(sample(), DIMENSIONS);

            List<Reconciler.Difference> top = Reconciler.top(described, 3);

            assertEquals(3, top.size());
            assertEquals(-5_000L, top.get(0).delta());
            assertEquals("plant=P2 | account=A2", top.get(0).coordinate());
            // Equal magnitude: the tie breaks on coordinate, so the report is stable across runs.
            assertEquals(-1_000L, top.get(1).delta());
            assertEquals("plant=P1 | account=A2", top.get(1).coordinate());
            assertEquals("plant=P2 | account=A3", top.get(2).coordinate());
        }

        @Test
        void csvUsesTheReferenceColumns() {
            String csv = Reconciler.toCsv(Reconciler.describe(sample(), DIMENSIONS), 2, DIMENSIONS);

            String[] lines = csv.split("\n");
            assertEquals("kind,account,coordinate,reference_amount,actual_amount,difference", lines[0]);
            assertEquals("DIFFERENT,A2,plant=P1 | account=A2,200.00,190.00,-10.00", lines[1]);
            assertEquals(5, lines.length);
        }

        @Test
        void renderIsTheIdentityWithoutDimensions() {
            assertEquals(key("P1", "A1"), Reconciler.render(key("P1", "A1"), null));
        }

        @Test
        void renderRejectsAKeyOfTheWrongArity() {
            assertThrows(AllocationException.class, () -> Reconciler.render(key("P1"), DIMENSIONS));
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        void everyDifferenceAccountedForAndDeltasSumToTheTotalGap() {
            Map<String, Long> expected = amounts();
            Map<String, Long> actual = amounts();
            // A deterministic spread rather than a random one: a failing assertion must be reproducible.
            for (int i = 0; i < 200; i++) {
                String coordinate = key("P" + (i % 7), "A" + (i % 13));
                if (i % 5 != 0) {
                    expected.put(coordinate, (i * 37L) % 1_000);
                }
                if (i % 11 != 0) {
                    actual.put(coordinate, (i * 41L) % 1_000);
                }
            }
            long expectedTotal = 0;
            for (long amount : expected.values()) {
                expectedTotal += amount;
            }
            long actualTotal = 0;
            for (long amount : actual.values()) {
                actualTotal += amount;
            }

            Reconciler.Report report = Reconciler.compare(expected, actual, Reconciler.Options.exact());

            long union = report.matched() + report.differed() + report.missing() + report.extra();
            assertEquals(expected.keySet().stream().distinct().count()
                    + actual.keySet().stream().filter(k -> !expected.containsKey(k)).count(), union);

            long deltaSum = 0;
            for (Reconciler.Difference difference : report.differences()) {
                deltaSum += difference.delta();
            }
            assertEquals(actualTotal - expectedTotal, deltaSum);
            assertEquals(expectedTotal, report.expectedTotal());
            assertEquals(actualTotal, report.actualTotal());
        }

        @Test
        void comparisonIsSymmetricInCounts() {
            Map<String, Long> left = amounts(key("P1", "A1"), 10L, key("P2", "A2"), 20L);
            Map<String, Long> right = amounts(key("P1", "A1"), 10L, key("P3", "A3"), 30L);

            Reconciler.Report forward = Reconciler.compare(left, right, Reconciler.Options.exact());
            Reconciler.Report backward = Reconciler.compare(right, left, Reconciler.Options.exact());

            assertEquals(forward.matched(), backward.matched());
            assertEquals(forward.missing(), backward.extra());
            assertEquals(forward.extra(), backward.missing());
            assertEquals(forward.expectedTotal(), backward.actualTotal());
        }
    }
}
