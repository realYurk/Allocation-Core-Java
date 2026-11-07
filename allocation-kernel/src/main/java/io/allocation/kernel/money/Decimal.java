package io.allocation.kernel.money;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;

import java.math.BigInteger;

/**
 * Exact decimal text encoding for monetary values.
 *
 * <p>A value is stored as a {@code long} holding the amount in units of {@code 10^-scale}. Parsing is
 * strict: text that carries more fractional digits than the configured scale is <em>rejected</em>,
 * never rounded. Silently rounding {@code 1.005} at scale 2 is how a penny disappears.
 *
 * <p>Both directions are bit-for-bit compatible with the Go reference encoder, including
 * {@link Long#MIN_VALUE} formatting and the exact set of characters treated as surrounding
 * whitespace. Compatibility here is not cosmetic: it is what makes byte-level differential testing
 * against the reference implementation possible at all.
 */
public final class Decimal {

    /** Maximum number of UTF-8 bytes accepted for a single decimal literal, after trimming. */
    public static final int MAX_TEXT_BYTES = 128;

    /** Lowest supported scale. */
    public static final int MIN_SCALE = 0;

    /** Highest supported scale. Scale above 6 has no currency meaning and invites precision drift. */
    public static final int MAX_SCALE = 6;

    private static final long[] POW10 = {
            1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L
    };

    private static final BigInteger BIG_TEN = BigInteger.TEN;
    private static final BigInteger BIG_MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger BIG_TWO_POW_63 = BigInteger.ONE.shiftLeft(63);

    private Decimal() {
    }

    /**
     * Parses a decimal literal into scaled integer units.
     *
     * <p>Accepted: optional {@code +/-}, digits, one optional decimal point, optional trailing point
     * ({@code "1."} is 1). Rejected: empty text, any non-digit, more than one point, more fractional
     * digits than {@code scale}, and any value outside the {@code long} range.
     *
     * @param raw   the literal, possibly padded with whitespace
     * @param scale fractional digits, {@code 0..6}
     * @return the value in units of {@code 10^-scale}
     * @throws AllocationException with {@link ErrorCode#AMOUNT_OUT_OF_RANGE} on any violation
     */
    public static long parseScaled(String raw, int scale) {
        requireScale(scale);
        if (raw == null) {
            throw invalid("empty decimal");
        }
        String s = trimGoSpace(raw);
        if (s.length() > MAX_TEXT_BYTES || utf8Length(s) > MAX_TEXT_BYTES) {
            throw invalid("decimal input exceeds " + MAX_TEXT_BYTES + " bytes");
        }
        if (s.isEmpty()) {
            throw invalid("empty decimal");
        }

        boolean negative = false;
        int start = 0;
        char first = s.charAt(0);
        if (first == '-') {
            negative = true;
            start = 1;
        } else if (first == '+') {
            start = 1;
        }
        String body = s.substring(start);

        int dot = body.indexOf('.');
        if (dot >= 0 && body.indexOf('.', dot + 1) >= 0) {
            throw invalid("invalid decimal \"" + s + '"');
        }
        String wholePart = dot < 0 ? body : body.substring(0, dot);
        String fracPart = dot < 0 ? null : body.substring(dot + 1);
        if (wholePart.isEmpty()) {
            throw invalid("invalid decimal \"" + s + '"');
        }
        requireDigits(wholePart, s);
        if (fracPart != null) {
            requireDigits(fracPart, s);
            if (fracPart.length() > scale) {
                throw invalid("decimal scale exceeds " + scale + ": " + s);
            }
        }

        if (wholePart.length() + scale <= 18) {
            return parseFast(wholePart, fracPart, scale, negative);
        }
        return parseBig(wholePart, fracPart, scale, negative, s);
    }

    /** Fast path: {@code wholePart.length() + scale <= 18} keeps every intermediate below 10^18. */
    private static long parseFast(String wholePart, String fracPart, int scale, boolean negative) {
        long whole = Long.parseLong(wholePart);
        long value = whole * POW10[scale];
        if (fracPart != null && !fracPart.isEmpty()) {
            long frac = 0L;
            for (int i = 0; i < scale; i++) {
                frac = frac * 10L + (i < fracPart.length() ? fracPart.charAt(i) - '0' : 0);
            }
            value += frac;
        }
        return negative ? -value : value;
    }

    private static long parseBig(String wholePart, String fracPart, int scale,
                                 boolean negative, String original) {
        BigInteger magnitude = new BigInteger(wholePart).multiply(BIG_TEN.pow(scale));
        if (fracPart != null && !fracPart.isEmpty()) {
            StringBuilder sb = new StringBuilder(fracPart);
            while (sb.length() < scale) {
                sb.append('0');
            }
            magnitude = magnitude.add(new BigInteger(sb.toString()));
        }
        if (negative) {
            // |Long.MIN_VALUE| is one larger than Long.MAX_VALUE and is a legal input.
            if (magnitude.compareTo(BIG_TWO_POW_63) == 0) {
                return Long.MIN_VALUE;
            }
            if (magnitude.compareTo(BIG_MAX_LONG) > 0) {
                throw invalid("decimal int64 overflow \"" + original + '"');
            }
            return magnitude.negate().longValue();
        }
        if (magnitude.compareTo(BIG_MAX_LONG) > 0) {
            throw invalid("decimal int64 overflow \"" + original + '"');
        }
        return magnitude.longValue();
    }

    private static void requireDigits(String part, String original) {
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c < '0' || c > '9') {
                throw invalid("invalid decimal \"" + original + '"');
            }
        }
    }

    private static void requireScale(int scale) {
        if (scale < MIN_SCALE || scale > MAX_SCALE) {
            throw invalid("decimal precision must be " + MIN_SCALE + ".." + MAX_SCALE + ": " + scale);
        }
    }

    /**
     * Formats scaled units as fixed-point text: no grouping, no exponent, exactly {@code scale} digits
     * after the point, {@code -0.01} rather than {@code 0.-1}.
     */
    public static String format(long units, int scale) {
        requireScale(scale);
        // Unsigned magnitude: -Long.MIN_VALUE wraps to Long.MIN_VALUE, whose unsigned value is 2^63.
        long magnitude = units < 0 ? -units : units;
        String sign = units < 0 ? "-" : "";
        if (scale == 0) {
            return sign + Long.toUnsignedString(magnitude);
        }
        long divisor = POW10[scale];
        long whole = Long.divideUnsigned(magnitude, divisor);
        long frac = Long.remainderUnsigned(magnitude, divisor);
        String fracText = Long.toUnsignedString(frac);
        StringBuilder sb = new StringBuilder(32);
        sb.append(sign).append(Long.toUnsignedString(whole)).append('.');
        for (int i = fracText.length(); i < scale; i++) {
            sb.append('0');
        }
        return sb.append(fracText).toString();
    }

    /** Units per whole for a scale, e.g. scale 2 yields 100. */
    public static long unitsPerWhole(int scale) {
        requireScale(scale);
        return POW10[scale];
    }

    private static AllocationException invalid(String message) {
        return new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE, message);
    }

    /**
     * Trims exactly the characters the reference implementation trims.
     *
     * <p>This is the Unicode {@code White_Space} property, which is <em>not</em> identical to
     * {@link Character#isWhitespace(int)}: it includes U+00A0, U+2007 and U+202F, and excludes
     * U+001C..U+001F. Using {@link String#strip()} here would accept or reject different inputs.
     */
    private static String trimGoSpace(String s) {
        int begin = 0;
        int end = s.length();
        while (begin < end) {
            int cp = s.codePointAt(begin);
            if (!isGoSpace(cp)) {
                break;
            }
            begin += Character.charCount(cp);
        }
        while (end > begin) {
            int cp = s.codePointBefore(end);
            if (!isGoSpace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return s.substring(begin, end);
    }

    /** Unicode {@code White_Space} property, matching the reference {@code strings.TrimSpace}. */
    static boolean isGoSpace(int cp) {
        return cp == 0x09 || cp == 0x0A || cp == 0x0B || cp == 0x0C || cp == 0x0D
                || cp == 0x20 || cp == 0x85 || cp == 0xA0 || cp == 0x1680
                || (cp >= 0x2000 && cp <= 0x200A)
                || cp == 0x2028 || cp == 0x2029 || cp == 0x202F
                || cp == 0x205F || cp == 0x3000;
    }

    private static int utf8Length(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp < 0x80) {
                n += 1;
            } else if (cp < 0x800) {
                n += 2;
            } else if (cp < 0x10000) {
                n += 3;
            } else {
                n += 4;
            }
            if (n > MAX_TEXT_BYTES) {
                return n;
            }
            i += Character.charCount(cp);
        }
        return n;
    }
}
