package io.allocation.kernel.money;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The second witness for {@link Overflow}: the overflow guards are verified against an independent
 * {@link BigInteger} oracle rather than against the guards' own assumptions.
 *
 * <p>Overflow is the single most dangerous class of defect in this engine, because a wrapped amount
 * is <em>silent</em> — every conservation check downstream can still pass, and the books just read
 * "one party was clawed back". The guards exist to make wrapping impossible, so the property they
 * must satisfy is binary and absolute: {@code mulFits(x, y)} must equal exactly
 * {@code Long.MIN_VALUE <= x*y <= Long.MAX_VALUE}, for <em>every</em> {@code x, y}.
 *
 * <p>The {@code (x*y)/y == x} probe is a classic trick with two {@code MIN_VALUE / -1} traps where
 * the division itself wraps on the JVM; those are handled explicitly, and this test locks that
 * handling so a future refactor cannot reintroduce the wrap.
 */
class OverflowPropertiesTest {

    // ---------------------------------------------------------------- mulFits

    @Property(tries = 10_000)
    void mulFitsMatchesBigIntegerOracle(@ForAll("pair") Pair p) {
        boolean oracle = fits(p.x, p.y);
        assertEquals(oracle, Overflow.mulFits(p.x, p.y),
                "mulFits disagrees with the oracle at " + p);
    }

    @Test
    void mulFitsIsCorrectOnAdversarialBoundaries() {
        long[] edges = {Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L,
                Long.MAX_VALUE - 1, Long.MAX_VALUE,
                0x7fffffffL, -0x80000000L, 0x100000000L,
                3_037_000_499L, -3_037_000_499L, 1L << 16, -(1L << 16)};
        for (long x : edges) {
            for (long y : edges) {
                assertEquals(fits(x, y), Overflow.mulFits(x, y),
                        "mulFits at x=" + x + " y=" + y);
            }
        }
    }

    @Test
    void mulFitsIsCorrectOnTwentyMillionRandomInputs() {
        java.util.Random random = new java.util.Random(0x5EEDL);
        for (int i = 0; i < 20_000_000; i++) {
            long x = random.nextLong();
            long y = random.nextLong();
            if (fits(x, y) != Overflow.mulFits(x, y)) {
                throw new AssertionError("mulFits mismatch at x=" + x + " y=" + y);
            }
        }
    }

    // ---------------------------------------------------------------- subExact

    @Property(tries = 10_000)
    void subExactMatchesBigIntegerOracle(@ForAll("pair") Pair p) {
        BigInteger oracle = BigInteger.valueOf(p.x).subtract(BigInteger.valueOf(p.y));
        boolean fits = oracle.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0
                && oracle.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0;
        if (!fits) {
            assertThrows(AllocationArithmeticException.class, () -> Overflow.subExact(p.x, p.y),
                    "overflowing subtraction must throw at " + p);
            return;
        }
        assertEquals(oracle.longValueExact(), Overflow.subExact(p.x, p.y),
                "subExact disagrees with the oracle at " + p);
    }

    // ---------------------------------------------------------------- unsignedMagnitude

    @Property(tries = 10_000)
    void unsignedMagnitudeMatchesBigIntegerOracle(@ForAll long x) {
        // The magnitude is |x| as an unsigned 64-bit quantity; Long.MIN_VALUE maps to 2^63.
        BigInteger magnitude = BigInteger.valueOf(x).abs();
        String oracleUnsigned = magnitude.toString();
        String actual = Long.toUnsignedString(Overflow.unsignedMagnitude(x));
        assertEquals(oracleUnsigned, actual, "unsignedMagnitude at x=" + x);
    }

    // ---------------------------------------------------------------- negExact

    @Property(tries = 10_000)
    void negExactMatchesBigIntegerOracle(@ForAll long x) {
        if (x == Long.MIN_VALUE) {
            assertThrows(AllocationArithmeticException.class, () -> Overflow.negExact(x),
                    "negating MIN_VALUE must throw");
            return;
        }
        assertEquals(-x, Overflow.negExact(x), "negExact at x=" + x);
    }

    // ---------------------------------------------------------------- addExact / mulExact

    @Property(tries = 10_000)
    void addExactMatchesBigIntegerOracle(@ForAll("pair") Pair p) {
        BigInteger oracle = BigInteger.valueOf(p.x).add(BigInteger.valueOf(p.y));
        boolean fits = oracle.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0
                && oracle.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0;
        if (!fits) {
            assertThrows(AllocationArithmeticException.class, () -> Overflow.addExact(p.x, p.y),
                    "overflowing addition must throw at " + p);
            return;
        }
        assertEquals(oracle.longValueExact(), Overflow.addExact(p.x, p.y),
                "addExact disagrees with the oracle at " + p);
    }

    @Property(tries = 10_000)
    void mulExactMatchesBigIntegerOracle(@ForAll("pair") Pair p) {
        BigInteger oracle = BigInteger.valueOf(p.x).multiply(BigInteger.valueOf(p.y));
        boolean fits = oracle.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0
                && oracle.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0;
        if (!fits) {
            assertThrows(AllocationArithmeticException.class, () -> Overflow.mulExact(p.x, p.y),
                    "overflowing multiplication must throw at " + p);
            return;
        }
        assertEquals(oracle.longValueExact(), Overflow.mulExact(p.x, p.y),
                "mulExact disagrees with the oracle at " + p);
    }

    // ---------------------------------------------------------------- oracle

    /** Independent: {@code x*y} fits a signed long iff it lies in [MIN_VALUE, MAX_VALUE]. */
    private static boolean fits(long x, long y) {
        BigInteger product = BigInteger.valueOf(x).multiply(BigInteger.valueOf(y));
        return product.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0
                && product.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0;
    }

    private record Pair(long x, long y) {
        @Override
        public String toString() {
            return "(" + x + ", " + y + ")";
        }
    }

    @Provide
    Arbitrary<Pair> pair() {
        // Bias toward the values most likely to wrap: the extremes, small powers, and the square
        // root boundary where a 64-bit product first overflows. Pure uniform longs would almost
        // always overflow, which still validates the throw path but under-tests the boundary.
        Arbitrary<Long> interesting = Arbitraries.of(
                Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L,
                Long.MAX_VALUE - 1, Long.MAX_VALUE,
                0x7fffffffL, -0x80000000L, 0x100000000L,
                3_037_000_499L, -3_037_000_499L);
        // Half the samples pair two interesting values (the boundary-rich case), half pair an
        // interesting value with a uniform random long (the general case).
        Arbitrary<Boolean> boundaryRich = Arbitraries.of(true, false);
        return Combinators.combine(interesting, interesting, Arbitraries.longs(), boundaryRich)
                .as((a, b, c, useAB) -> useAB ? new Pair(a, b) : new Pair(b, c));
    }
}
