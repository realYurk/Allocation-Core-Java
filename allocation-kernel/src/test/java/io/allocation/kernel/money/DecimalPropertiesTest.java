package io.allocation.kernel.money;

import io.allocation.kernel.AllocationException;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The second witness for {@link Decimal}: parsing and formatting are checked against independent
 * {@link BigInteger} transcriptions, so a value is correct by arithmetic, not just because a
 * hand-picked vector happened to match.
 *
 * <p>{@link Decimal#parseScaled} has two code paths — a fast {@code long} path entered when
 * {@code wholePart.length() + scale <= 18}, and an arbitrary-precision path otherwise — plus a
 * deliberate asymmetry at {@link Long#MIN_VALUE} whose magnitude is one larger than
 * {@link Long#MAX_VALUE}. A defect that leaves both paths wrong in the same way is invisible to a
 * production round-trip but not to a third, independent calculation.
 */
class DecimalPropertiesTest {

    // ---------------------------------------------------------------- parse correctness (independent oracle)

    @Property(tries = 20_000)
    void parseScaledMatchesIndependentOracle(@ForAll long units, @ForAll("scale") int scale) {
        // Build a valid literal from an arbitrary value using an independent formatter, then verify
        // the production parser recovers the same integer the independent parser would. Three
        // transcriptions — independent format, independent parse, production parse — all meet here.
        String text = formatByBigInteger(units, scale);
        BigInteger oracle = parseByBigInteger(text, scale);
        assertEquals(units, oracle.longValueExact(),
                "independent oracle should round-trip its own output");
        assertEquals(units, Decimal.parseScaled(text, scale),
                "production parser disagrees with the oracle at \"" + text + "\" @ " + scale);
    }

    // ---------------------------------------------------------------- format correctness (independent oracle)

    @Property(tries = 20_000)
    void formatMatchesIndependentOracle(@ForAll long units, @ForAll("scale") int scale) {
        assertEquals(formatByBigInteger(units, scale), Decimal.format(units, scale),
                "production formatter disagrees with the oracle for " + units + " @ " + scale);
    }

    // ---------------------------------------------------------------- round-trip (production only)

    @Property(tries = 20_000)
    void formatThenParseRoundTrips(@ForAll long units, @ForAll("scale") int scale) {
        String text = Decimal.format(units, scale);
        assertEquals(units, Decimal.parseScaled(text, scale),
                "round-trip failed for " + units + " @ " + scale + " -> \"" + text + "\"");
    }

    // ---------------------------------------------------------------- fast/big path boundary

    @Test
    void fastAndBigPathsAgreeAtTheThreshold() {
        // The fast path triggers when wholePart.length() + scale <= 18. A whole part of 18 digits at
        // scale 0 is the largest fast-path case; 19 digits (still < Long.MAX_VALUE's 19 digits) rides
        // the big path. Both must parse to the correct integer.
        assertEquals(999_999_999_999_999_999L, Decimal.parseScaled("999999999999999999", 0));
        assertEquals(1_000_000_000_000_000_000L, Decimal.parseScaled("1000000000000000000", 0));
        // Long.MAX_VALUE is 19 digits and must land exactly on the big path.
        assertEquals(Long.MAX_VALUE, Decimal.parseScaled("9223372036854775807", 0));
        // The same value spelled with a trailing ".0" at scale 1 is a different, valid input.
        assertEquals(9_223_372_036_854_775_807L, Decimal.parseScaled("922337203685477580.7", 1));
    }

    // ---------------------------------------------------------------- MIN_VALUE asymmetry

    @Test
    void minValueMagnitudeExceedsMaxByOne() {
        // -9223372036854775808 parses and formats losslessly; its magnitude (2^63) is one more than
        // Long.MAX_VALUE, which is exactly the case the arbitrary-precision path special-cases.
        assertEquals(Long.MIN_VALUE, Decimal.parseScaled("-9223372036854775808", 0));
        assertEquals("-9223372036854775808", Decimal.format(Long.MIN_VALUE, 0));
        assertEquals(Long.MIN_VALUE, Decimal.parseScaled("-92233720368547758.08", 2));
        assertEquals("-92233720368547758.08", Decimal.format(Long.MIN_VALUE, 2));
    }

    // ---------------------------------------------------------------- independent oracles

    /**
     * Parses a decimal literal into a scaled {@link BigInteger}, or {@code null} when malformed or
     * out of the signed long range. The grammar is transcribed from the specification and shares no
     * code with {@link Decimal}.
     */
    private static BigInteger parseByBigInteger(String text, int scale) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (s.isEmpty()) {
            return null;
        }
        boolean negative = false;
        int start = 0;
        if (s.charAt(0) == '-' || s.charAt(0) == '+') {
            negative = s.charAt(0) == '-';
            start = 1;
            if (start == s.length()) {
                return null;
            }
        }
        String body = s.substring(start);
        int dot = body.indexOf('.');
        if (dot >= 0 && body.indexOf('.', dot + 1) >= 0) {
            return null;
        }
        String whole = dot < 0 ? body : body.substring(0, dot);
        String frac = dot < 0 ? "" : body.substring(dot + 1);
        if (whole.isEmpty() || !isAllDigits(whole) || !isAllDigits(frac) || frac.length() > scale) {
            return null;
        }
        BigInteger magnitude = new BigInteger(whole).multiply(BigInteger.TEN.pow(scale));
        if (!frac.isEmpty()) {
            magnitude = magnitude.add(
                    new BigInteger(frac).multiply(BigInteger.TEN.pow(scale - frac.length())));
        }
        if (negative) {
            magnitude = magnitude.negate();
        }
        if (magnitude.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0
                || magnitude.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return null;
        }
        return magnitude;
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** Formats {@code units} at {@code scale} using only {@link BigInteger}, independently of {@link Decimal}. */
    private static String formatByBigInteger(long units, int scale) {
        BigInteger value = BigInteger.valueOf(units);
        boolean negative = value.signum() < 0;
        BigInteger[] qr = value.abs().divideAndRemainder(BigInteger.TEN.pow(scale));
        String whole = qr[0].toString();
        String frac = qr[1].toString();
        while (frac.length() < scale) {
            frac = "0" + frac;
        }
        StringBuilder sb = new StringBuilder();
        if (negative) {
            sb.append('-');
        }
        sb.append(whole);
        if (scale > 0) {
            sb.append('.').append(frac);
        }
        return sb.toString();
    }

    @Provide
    Arbitrary<Integer> scale() {
        return Arbitraries.integers().between(0, 6);
    }
}
