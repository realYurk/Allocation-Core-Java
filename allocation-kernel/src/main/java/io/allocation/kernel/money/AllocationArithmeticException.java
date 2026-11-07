package io.allocation.kernel.money;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;

/**
 * Raised when an exact integer operation leaves the representable range.
 *
 * <p>This is always a fail-closed condition: no partial result is produced, no evidence is written.
 * The dedicated subtype exists so callers can map it to {@code ALLOC_002} without inspecting messages.
 */
public class AllocationArithmeticException extends AllocationException {

    private static final long serialVersionUID = 1L;

    public AllocationArithmeticException(String message) {
        super(ErrorCode.AMOUNT_OUT_OF_RANGE, message);
    }
}
