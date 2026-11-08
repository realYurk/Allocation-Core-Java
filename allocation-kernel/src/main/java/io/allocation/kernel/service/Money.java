package io.allocation.kernel.service;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.money.Decimal;
import io.allocation.kernel.money.Overflow;

/**
 * The only amount type a caller sees.
 *
 * <p>Inside the kernel an amount is a bare {@code long} in units of 10<sup>-precision</sup>, because
 * that is what keeps the arithmetic exact and fast. At the boundary a {@code long} is dangerous: it is
 * easy to pass a number of yuan where a number of cents was expected. Carrying the scale alongside the
 * units makes that mistake a compile-time-shaped one rather than a silent factor of a hundred.
 *
 * @param units integer count of 10<sup>-scale</sup> units
 * @param scale decimal places, 0..6
 */
public record Money(long units, int scale) {

    public Money {
        if (scale < 0 || scale > 6) {
            throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE, "scale must be 0..6, got " + scale);
        }
    }

    public static Money zero(int scale) {
        return new Money(0L, scale);
    }

    /** Parses a decimal literal at the given scale, rejecting excess fractional digits. */
    public static Money parse(String text, int scale) {
        return new Money(Decimal.parseScaled(text, scale), scale);
    }

    /** Canonical text form, for example {@code 1234.56} or {@code -0.01}. */
    public String format() {
        return Decimal.format(units, scale);
    }

    /**
     * Converts to the unit count of another precision.
     *
     * <p>Widening is exact; narrowing is refused rather than rounded, because a service that silently
     * rounds an amount has changed the money it was asked to keep intact.
     */
    public long unitsAt(int precision) {
        if (precision == scale) {
            return units;
        }
        if (precision < scale) {
            throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                    "amount " + format() + " needs scale " + scale + ", rule precision is " + precision);
        }
        long factor = 1L;
        for (int i = 0; i < precision - scale; i++) {
            factor *= 10L;
        }
        return Overflow.mulExact(units, factor);
    }

    @Override
    public String toString() {
        return format();
    }
}
