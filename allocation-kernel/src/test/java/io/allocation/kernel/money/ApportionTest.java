package io.allocation.kernel.money;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.coord.Utf8Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance vectors for the splitting algorithm. Every expectation here was produced by the Go
 * reference implementation and is frozen; changing one is a behaviour change and requires a
 * {@code [SEMANTICS]} commit.
 */
class ApportionTest {

    private static final String[] ABC = {"A", "B", "C"};

    @Test
    @DisplayName("1.00 across three equal weights settles on the first identity")
    void splitsEvenlyWithPositiveResidual() {
        assertArrayEquals(new long[]{34, 33, 33},
                Apportion.split(100, new long[]{1, 1, 1}, ABC, false));
    }

    @Test
    @DisplayName("1.01 across three equal weights gives the residual to the first two")
    void splitsWithResidualOfTwo() {
        assertArrayEquals(new long[]{34, 34, 33},
                Apportion.split(101, new long[]{1, 1, 1}, ABC, false));
    }

    @Test
    @DisplayName("one cent across three targets goes to exactly one of them")
    void splitsSingleUnit() {
        assertArrayEquals(new long[]{1, 0, 0},
                Apportion.split(1, new long[]{1, 1, 1}, ABC, false));
    }

    @Test
    @DisplayName("negative totals mirror positive ones instead of rounding the other way")
    void splitsNegativeTotal() {
        assertArrayEquals(new long[]{-34, -33, -33},
                Apportion.split(-100, new long[]{1, 1, 1}, ABC, false));
        // Mirror of +101 -> [34, 34, 33]; the same two targets absorb the negative residual.
        assertArrayEquals(new long[]{-34, -34, -33},
                Apportion.split(-101, new long[]{1, 1, 1}, ABC, false));
    }

    @Test
    @DisplayName("signed drivers are permitted only when explicitly allowed")
    void splitsSignedWeights() {
        assertArrayEquals(new long[]{150, -50},
                Apportion.split(100, new long[]{3, -1}, new String[]{"A", "B"}, true));
    }

    @Test
    @DisplayName("a negative weight without permission is rejected")
    void rejectsNegativeWeight() {
        AllocationException ex = assertThrows(AllocationException.class,
                () -> Apportion.split(100, new long[]{3, -1}, new String[]{"A", "B"}, false));
        assertEquals("ALLOC_001", ex.code().code());
    }

    @Test
    @DisplayName("a single target receives the whole amount, including Long.MIN_VALUE")
    void handlesSingleTarget() {
        assertArrayEquals(new long[]{Long.MIN_VALUE},
                Apportion.split(Long.MIN_VALUE, new long[]{1}, new String[]{"A"}, false));
        assertArrayEquals(new long[]{12345}, Apportion.split(12345, new long[]{7}, new String[]{"A"}, false));
    }

    @Test
    @DisplayName("zero weights are a rejected denominator, not a silent all-zero result")
    void rejectsZeroDenominator() {
        assertThrows(AllocationException.class,
                () -> Apportion.split(100, new long[]{0, 0}, new String[]{"A", "B"}, false));
        assertThrows(AllocationException.class,
                () -> Apportion.split(100, new long[]{1, -1}, new String[]{"A", "B"}, true));
    }

    @Test
    @DisplayName("empty input is rejected rather than treated as a no-op")
    void rejectsEmpty() {
        assertThrows(AllocationException.class, () -> Apportion.split(100, new long[0], new String[0], false));
        assertThrows(AllocationException.class,
                () -> Apportion.split(100, new long[]{1}, new String[]{"A", "B"}, false));
    }

    @Test
    @DisplayName("ties break on identity, so reordering the input cannot move a cent")
    void tieBreaksOnIdentityNotPosition() {
        long[] forward = Apportion.split(100, new long[]{1, 1, 1}, new String[]{"A", "B", "C"}, false);
        long[] reversed = Apportion.split(100, new long[]{1, 1, 1}, new String[]{"C", "B", "A"}, false);
        assertArrayEquals(new long[]{34, 33, 33}, forward);
        assertArrayEquals(new long[]{33, 33, 34}, reversed);
    }

    @Test
    @DisplayName("supplementary characters break ties by code point, not UTF-16 unit")
    void tieBreaksByCodePoint() {
        // U+4E00 (CJK) is below U+1F600 (emoji) by code point, but above it by UTF-16 unit.
        String cjk = "\u4E00";
        String emoji = "\uD83D\uDE00";
        long[] result = Apportion.split(1, new long[]{1, 1}, new String[]{emoji, cjk}, false);
        assertArrayEquals(new long[]{0, 1}, result);
    }

    @Test
    @DisplayName("conservation holds for every scale and weight distribution")
    void conservationHolds() {
        java.util.Random random = new java.util.Random(20261107L);
        for (int trial = 0; trial < 20_000; trial++) {
            int n = 1 + random.nextInt(12);
            long[] weights = new long[n];
            String[] ids = new String[n];
            for (int i = 0; i < n; i++) {
                weights[i] = 1 + random.nextInt(1_000_000);
                ids[i] = "T" + i;
            }
            long total = random.nextLong() % 10_000_000L;
            long[] parts = Apportion.split(total, weights, ids, false);
            assertEquals(total, Arrays.stream(parts).sum(), "sum mismatch at trial " + trial);
        }
    }

    @Test
    @DisplayName("fast path and arbitrary-precision path agree on adversarial magnitudes")
    void fastPathAgreesWithBigPath() {
        long[] totals = {Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1,
                Integer.MAX_VALUE, -Integer.MAX_VALUE, 1, -1, 0, 999_999_999_999L};
        long[][] weightSets = {
                {1},
                {1, 1},
                {1, 1, 1},
                {Long.MAX_VALUE},
                {Long.MAX_VALUE, 1},
                {Long.MAX_VALUE, Long.MAX_VALUE},
                {3, -1},
                {1_000_000, 2_000_000, 3_000_000},
                {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE},
        };
        for (long total : totals) {
            for (long[] weights : weightSets) {
                String[] ids = new String[weights.length];
                for (int i = 0; i < ids.length; i++) {
                    ids[i] = "S" + i;
                }
                String label = "total=" + total + " weights=" + Arrays.toString(weights);
                BigInteger[] expected = referenceSplit(total, weights, ids);
                boolean expectedRepresentable = true;
                for (BigInteger value : expected) {
                    if (value.bitLength() > 63) {
                        expectedRepresentable = false;
                        break;
                    }
                }
                if (!expectedRepresentable) {
                    assertThrows(AllocationException.class,
                            () -> Apportion.split(total, weights, ids, true),
                            "expected rejection: " + label);
                    continue;
                }
                long[] actual = Apportion.split(total, weights, ids, true);
                for (int i = 0; i < actual.length; i++) {
                    assertEquals(expected[i].longValueExact(), actual[i], "mismatch " + label + " i=" + i);
                }
            }
        }
    }

    /**
     * Independent transcription of the specification, using only {@link BigInteger}. Deliberately
     * written without sharing any code with the implementation under test.
     */
    private static BigInteger[] referenceSplit(long total, long[] weights, String[] ids) {
        BigInteger denom = BigInteger.ZERO;
        for (long w : weights) {
            denom = denom.add(BigInteger.valueOf(w));
        }
        if (denom.signum() <= 0) {
            throw new IllegalStateException("non-positive denominator");
        }
        int n = weights.length;
        BigInteger[] values = new BigInteger[n];
        BigInteger[] rems = new BigInteger[n];
        BigInteger allocated = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            BigInteger[] qr = BigInteger.valueOf(total).multiply(BigInteger.valueOf(weights[i]))
                    .divideAndRemainder(denom);
            values[i] = qr[0];
            rems[i] = qr[1];
            allocated = allocated.add(qr[0]);
        }
        BigInteger residual = BigInteger.valueOf(total).subtract(allocated);
        int res = residual.intValueExact();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        boolean positive = res > 0;
        Arrays.sort(order, (a, b) -> {
            int cmp = rems[a].compareTo(rems[b]);
            if (cmp != 0) {
                return positive ? -cmp : cmp;
            }
            return Utf8Order.compare(ids[a], ids[b]);
        });
        int magnitude = Math.abs(res);
        for (int i = 0; i < magnitude; i++) {
            int ix = order[i % n];
            values[ix] = values[ix].add(BigInteger.valueOf(positive ? 1 : -1));
        }
        return values;
    }
}
