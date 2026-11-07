package io.allocation.kernel.money;

/**
 * Exact 64-bit arithmetic helpers.
 *
 * <p>CONSTITUTION Art.3: a bare {@code a + b} or {@code a * b} on a monetary or weight value is a
 * defect, including when the author "knows" it cannot overflow. {@code long} overflow is silent:
 * a wrapped negative amount reads as "one party was clawed back", and every conservation check
 * downstream can still pass.
 *
 * <p>Every helper here is either checked (throws {@link AllocationArithmeticException}) or proves
 * the operation fits before performing it. Nothing wraps.
 */
public final class Overflow {

    private Overflow() {
    }

    /** Adds two values, throwing on overflow instead of wrapping. */
    public static long addExact(long x, long y) {
        try {
            return Math.addExact(x, y);
        } catch (ArithmeticException e) {
            throw new AllocationArithmeticException("amount overflow: " + x + " + " + y);
        }
    }

    /**
     * Subtracts {@code y} from {@code x} without negating {@code Long.MIN_VALUE}.
     *
     * <p>The naive {@code addExact(x, -y)} fails for {@code y == Long.MIN_VALUE} because {@code -y}
     * is not representable. The reference implementation special-cases it and yields a value; we
     * must produce the same value, not merely "an error".
     */
    public static long subExact(long x, long y) {
        if (y == Long.MIN_VALUE) {
            if (x >= 0) {
                throw new AllocationArithmeticException("amount overflow: " + x + " - " + y);
            }
            // For negative x, x - MIN_VALUE lands in [0, Long.MAX_VALUE] and cannot wrap.
            return x - y;
        }
        return addExact(x, -y);
    }

    /** Multiplies two values, throwing on overflow instead of wrapping. */
    public static long mulExact(long x, long y) {
        try {
            return Math.multiplyExact(x, y);
        } catch (ArithmeticException e) {
            throw new AllocationArithmeticException("amount overflow: " + x + " * " + y);
        }
    }

    /**
     * True when {@code x * y} is representable as a {@code long}.
     *
     * <p>Uses the classic exactness probe {@code (x*y)/y == x}, with the two {@code MIN_VALUE / -1}
     * cases handled explicitly because that division itself wraps on the JVM.
     */
    public static boolean mulFits(long x, long y) {
        if (x == 0L || y == 0L) {
            return true;
        }
        if ((x == Long.MIN_VALUE && y == -1L) || (y == Long.MIN_VALUE && x == -1L)) {
            return false;
        }
        return (x * y) / y == x;
    }

    /** Negates a value, throwing when the value is {@code Long.MIN_VALUE}. */
    public static long negExact(long x) {
        if (x == Long.MIN_VALUE) {
            throw new AllocationArithmeticException("amount overflow: -(" + x + ")");
        }
        return -x;
    }

    /**
     * Magnitude of a value as an <em>unsigned</em> 64-bit quantity.
     *
     * <p>Negating {@link Long#MIN_VALUE} wraps to itself, and that bit pattern is exactly 2^63 when
     * read as unsigned — which is the magnitude we want. So the plain negation is correct as long as
     * every consumer treats the result as unsigned ({@link Long#toUnsignedString},
     * {@link Long#divideUnsigned}). The tempting alternative {@code -(x + 1) + 1} overflows in signed
     * Java precisely where the reference implementation relies on unsigned wrap-around.
     */
    public static long unsignedMagnitude(long x) {
        return x < 0 ? -x : x;
    }
}
