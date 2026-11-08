package io.allocation.kernel.money;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.coord.Utf8Order;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The second witness (W2): an independent, arbitrary-precision oracle for {@link Apportion}.
 *
 * <p>The golden corpus (W1) proves the engine still computes what the Go reference computed. But if
 * the Go reference itself got a boundary wrong, the corpus would have frozen that wrong answer, and a
 * faithful port would reproduce it forever — agreement between two copies of the same mistake is not
 * correctness. This test is the countermeasure: it re-derives every split from the specification
 * using only {@link BigInteger} fractions, sharing no code with the production fast path, and asserts
 * the two agree on inputs the corpus never freezes.
 *
 * <p>The oracle path is deliberately different from production: production truncates {@code total*w[i]}
 * with a {@code long} divide and settles the residual by remainder rank; the oracle computes exact
 * rationals {@code total*w[i]/denom} and compares by cross-multiplication, never dividing. A defect
 * that leaves both production paths wrong but mutually consistent is still caught here.
 *
 * <p>This is what {@link Apportion}'s class comment means by "property tests assert they agree on
 * millions of random inputs": it was the promise; this is the evidence.
 */
class ApportionPropertiesTest {

    // ---------------------------------------------------------------- P1: conservation

    @Property(tries = 2000)
    void conservationHolds(@ForAll("split") Split split) {
        long[] out = Apportion.split(split.total, split.weights, split.identities, split.allowSigned);
        assertEquals(split.total, sumExact(out), "sum must equal total: " + split);
    }

    // ---------------------------------------------------------------- P2: non-negativity

    @Property(tries = 2000)
    void nonNegativeInputsStayNonNegative(@ForAll("nonNegative") Split split) {
        long[] out = Apportion.split(split.total, split.weights, split.identities, false);
        for (long v : out) {
            assertTrue(v >= 0, "negative share from non-negative input: " + split + " -> " + Arrays.toString(out));
        }
    }

    // ---------------------------------------------------------------- P3: monotonicity

    @Property(tries = 2000)
    void monotoneSharesTrackWeights(@ForAll("nonNegative") Split split) {
        // Only meaningful for strictly positive totals with distinct weights.
        if (split.total <= 0) {
            return;
        }
        long[] out = Apportion.split(split.total, split.weights, split.identities, false);
        for (int i = 0; i < out.length; i++) {
            for (int j = 0; j < out.length; j++) {
                if (split.weights[i] < split.weights[j]) {
                    assertTrue(out[i] <= out[j] + 1,
                            "larger weight must not lose by more than one unit: " + split);
                }
            }
        }
    }

    // ---------------------------------------------------------------- P4: permutation invariance

    @Property(tries = 2000)
    void reorderingInputCannotMoveMoney(@ForAll("split") Split split) {
        long[] forward = Apportion.split(split.total, split.weights, split.identities, split.allowSigned);
        // Shuffle the array deterministically: reverse it.
        int n = split.weights.length;
        long[] revWeights = new long[n];
        String[] revIds = new String[n];
        for (int i = 0; i < n; i++) {
            revWeights[i] = split.weights[n - 1 - i];
            revIds[i] = split.identities[n - 1 - i];
        }
        long[] reversed = Apportion.split(split.total, revWeights, revIds, split.allowSigned);
        for (int i = 0; i < n; i++) {
            String id = split.identities[i];
            long forwardValue = forward[i];
            // Find the same identity in the reversed output.
            long reversedValue = reversed[n - 1 - i];
            assertEquals(forwardValue, reversedValue,
                    "reordering moved money for identity '" + id + "': " + split);
        }
    }

    // ---------------------------------------------------------------- P5: idempotence

    @Property(tries = 500)
    void sameInputTwiceIsSameOutput(@ForAll("split") Split split) {
        long[] a = Apportion.split(split.total, split.weights, split.identities, split.allowSigned);
        long[] b = Apportion.split(split.total, split.weights, split.identities, split.allowSigned);
        assertTrue(Arrays.equals(a, b), "split is not deterministic: " + split);
    }

    // ---------------------------------------------------------------- P6: residual boundedness

    @Property(tries = 2000)
    void residualTouchesAtMostRankCount(@ForAll("nonNegative") Split split) {
        long[] out = Apportion.split(split.total, split.weights, split.identities, false);
        // A truncating split differs from the settled split on at most |residual| entries, and
        // |residual| <= n. The oracle below already checks this exhaustively; here we assert the
        // looser, independent bound: no share deviates from its truncation by more than one unit.
        long denominator = 0L;
        for (long w : split.weights) {
            denominator = Math.addExact(denominator, w);
        }
        for (int i = 0; i < out.length; i++) {
            if (split.weights[i] == 0) {
                // zero weight can still receive the residual unit, so only bound the deviation.
                assertTrue(Math.abs(out[i]) <= 1, "zero-weight share deviated beyond a residual unit");
                continue;
            }
            long truncated = Math.multiplyExact(split.total, split.weights[i]) / denominator;
            long deviation = out[i] - truncated;
            assertTrue(Math.abs(deviation) <= 1,
                    "share deviated from truncation by more than one unit: " + split);
        }
    }

    // ---------------------------------------------------------------- P7: zero weight

    @Property(tries = 1000)
    void zeroWeightGetsAtMostOneUnit(@ForAll("nonNegative") Split split) {
        long[] out = Apportion.split(split.total, split.weights, split.identities, false);
        for (int i = 0; i < out.length; i++) {
            if (split.weights[i] == 0) {
                assertTrue(out[i] >= 0 && out[i] <= 1,
                        "zero weight must receive 0 or the residual +1: " + split);
            }
        }
    }

    // ---------------------------------------------------------------- P8: single target

    @Property(tries = 1000)
    void singleTargetGetsWholeAmount(@ForAll long total) {
        long[] out = Apportion.split(total, new long[]{1}, new String[]{"A"}, false);
        assertEquals(total, out[0], "single target must receive the whole amount");
    }

    // ---------------------------------------------------------------- the million-input agreement

    /**
     * The fast path and the arbitrary-precision oracle agree on a large sweep of random inputs,
     * including the magnitudes that force {@code total * weight} and the denominator out of a
     * {@code long}. This is the property the class comment promises.
     */
    @Test
    void fastPathAndGeneralPathAgreeOnMillionRandomInputs() {
        java.util.Random random = new java.util.Random(20261108L);
        // Deterministic seed so the "million" is reproducible, not a lottery.
        for (int trial = 0; trial < 1_000_000; trial++) {
            int n = 1 + random.nextInt(30);
            long[] weights = new long[n];
            String[] ids = new String[n];
            for (int i = 0; i < n; i++) {
                weights[i] = randomWeight(random);
                ids[i] = randomIdentity(random, i);
            }
            long total = randomTotal(random);

            // The production contract: a negative weight without allowSigned is rejected, and a
            // non-positive denominator is rejected. The oracle models only the mathematically valid
            // core (positive denominator, non-negative weights), so skip the inputs that fall
            // outside that core — those are exercised by ApportionTest's rejection vectors instead.
            long denominator = 0L;
            boolean withinCore = true;
            for (long w : weights) {
                denominator += w;
                if (w < 0) {
                    withinCore = false;
                    break;
                }
            }
            if (!withinCore || denominator <= 0) {
                continue;
            }

            BigInteger[] expected = referenceSplit(total, weights, ids);
            long[] actual;
            try {
                actual = Apportion.split(total, weights, ids, false);
            } catch (AllocationException reject) {
                // The only legitimate rejection on a valid core input is a genuine amount overflow,
                // where the oracle's exact answer does not fit in a signed 64-bit long.
                boolean overflowed = false;
                for (BigInteger v : expected) {
                    if (v.bitLength() > 63) {
                        overflowed = true;
                        break;
                    }
                }
                if (!overflowed) {
                    throw new AssertionError("rejected representable split at trial " + trial
                            + " total=" + total + " weights=" + Arrays.toString(weights)
                            + " code=" + reject.code(), reject);
                }
                continue;
            }
            for (int i = 0; i < n; i++) {
                if (expected[i].longValueExact() != actual[i]) {
                    throw new AssertionError("mismatch at trial " + trial + " total=" + total
                            + " weights=" + Arrays.toString(weights) + " i=" + i
                            + " expected=" + expected[i] + " actual=" + actual[i]);
                }
            }
        }
    }

    // ---------------------------------------------------------------- oracle (independent transcription)

    /**
     * The specification, transcribed independently with {@link BigInteger}. This is W2: it shares no
     * code with {@link Apportion}, computes exact rationals, and breaks ties by UTF-8 code point —
     * the same tie-break the specification mandates, not the production code's position.
     */
    private static BigInteger[] referenceSplit(long total, long[] weights, String[] ids) {
        int n = weights.length;
        BigInteger denom = BigInteger.ZERO;
        for (long w : weights) {
            denom = denom.add(BigInteger.valueOf(w));
        }
        if (denom.signum() <= 0) {
            throw new IllegalStateException("non-positive denominator");
        }
        BigInteger bigTotal = BigInteger.valueOf(total);
        BigInteger[] q = new BigInteger[n];
        BigInteger[] rems = new BigInteger[n];
        BigInteger allocated = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            BigInteger[] qr = bigTotal.multiply(BigInteger.valueOf(weights[i]))
                    .divideAndRemainder(denom);
            q[i] = qr[0];
            rems[i] = qr[1];
            allocated = allocated.add(qr[0]);
        }
        BigInteger residual = bigTotal.subtract(allocated);
        int res = residual.intValueExact();
        boolean positive = res > 0;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            int cmp = rems[a].compareTo(rems[b]);
            if (cmp != 0) {
                return positive ? -cmp : cmp;
            }
            return Utf8Order.compare(ids[a], ids[b]);
        });
        int magnitude = Math.abs(res);
        BigInteger[] values = new BigInteger[n];
        for (int i = 0; i < n; i++) {
            values[i] = q[i];
        }
        for (int i = 0; i < magnitude; i++) {
            int ix = order[i % n];
            values[ix] = values[ix].add(BigInteger.valueOf(positive ? 1 : -1));
        }
        return values;
    }

    // ---------------------------------------------------------------- generators

    private record Split(long total, long[] weights, String[] identities, boolean allowSigned) {
        @Override
        public String toString() {
            return "total=" + total + " weights=" + Arrays.toString(weights)
                    + " ids=" + Arrays.toString(identities) + " signed=" + allowSigned;
        }
    }

    @Provide
    Arbitrary<Split> split() {
        Arbitrary<Long> totals = Arbitraries.longs()
                .between(-10_000_000L, 10_000_000L);
        Arbitrary<Boolean> signed = Arbitraries.of(true, false);
        return Combinators.combine(totals, signed).as((total, allowSigned) -> {
            // A non-signed split must not see a negative weight, or every property would just
            // exercise the rejection path (which ApportionTest already covers). Signed splits may
            // contain negatives to exercise the mirror-settlement path.
            long lower = allowSigned ? -3L : 0L;
            return Arbitraries.longs()
                    .between(lower, 10_000L)
                    .array(long[].class)
                    .ofSize(1)
                    .ofMaxSize(20)
                    .filter(ws -> {
                        long d = 0L;
                        for (long w : ws) {
                            d += w;
                        }
                        // The production contract requires a strictly positive denominator.
                        return d > 0;
                    })
                    .map(ws -> {
                        String[] ids = new String[ws.length];
                        for (int i = 0; i < ws.length; i++) {
                            ids[i] = "id" + i;
                        }
                        return new Split(total, ws, ids, allowSigned);
                    });
        }).flatMap(arb -> arb);
    }

    @Provide
    Arbitrary<Split> nonNegative() {
        Arbitrary<Long> totals = Arbitraries.longs()
                .between(0L, 10_000_000L);
        return Arbitraries.longs()
                .between(0L, 10_000L)
                .array(long[].class)
                .ofSize(1)
                .ofMaxSize(20)
                .filter(ws -> {
                    long d = 0L;
                    for (long w : ws) {
                        d += w;
                    }
                    return d > 0;
                })
                .flatMap(ws -> totals.map(total -> {
                    String[] ids = new String[ws.length];
                    for (int i = 0; i < ws.length; i++) {
                        ids[i] = "T" + i;
                    }
                    return new Split(total, ws, ids, false);
                }));
    }

    private static long randomWeight(java.util.Random random) {
        int shape = random.nextInt(6);
        return switch (shape) {
            case 0 -> 1;                                   // ties everywhere
            case 1 -> random.nextInt(100) + 1L;            // small
            case 2 -> random.nextInt(1_000_000) + 1L;      // medium
            case 3 -> (long) random.nextInt(2_000_000_000); // near 32-bit
            case 4 -> random.nextLong();                    // full 64-bit, may be negative
            default -> random.nextBoolean() ? 0 : Long.MAX_VALUE; // extreme / zero
        };
    }

    private static String randomIdentity(java.util.Random random, int i) {
        // A mix of ASCII and supplementary (emoji/CJK) characters to exercise UTF-8 vs UTF-16 order.
        int shape = random.nextInt(4);
        return switch (shape) {
            case 0 -> "T" + i;
            case 1 -> "T" + random.nextInt(1000);
            case 2 -> (random.nextBoolean() ? "\u4E00" : "\uD83D\uDE00") + random.nextInt(10);
            default -> "T" + i + (random.nextBoolean() ? "a" : "z");
        };
    }

    private static long randomTotal(java.util.Random random) {
        int shape = random.nextInt(5);
        return switch (shape) {
            case 0 -> random.nextLong() % 10_000_000L;   // small
            case 1 -> random.nextLong() % 1_000_000_000L; // medium
            case 2 -> Long.MAX_VALUE;                     // extreme
            case 3 -> Long.MIN_VALUE;
            default -> random.nextLong();                 // full range
        };
    }

    private static long sumExact(long[] values) {
        long sum = 0L;
        for (long v : values) {
            sum = Math.addExact(sum, v);
        }
        return sum;
    }
}
