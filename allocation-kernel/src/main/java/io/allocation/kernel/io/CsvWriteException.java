package io.allocation.kernel.io;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;

/** Raised when evidence or result output cannot be written. Always fatal. */
public class CsvWriteException extends AllocationException {

    private static final long serialVersionUID = 1L;

    public CsvWriteException(String message, Throwable cause) {
        super(ErrorCode.INTERNAL, message, cause);
    }

    public CsvWriteException(String message) {
        super(ErrorCode.INTERNAL, message);
    }
}
