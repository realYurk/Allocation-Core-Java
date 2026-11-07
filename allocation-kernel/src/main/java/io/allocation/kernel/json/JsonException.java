package io.allocation.kernel.json;

/** Raised for malformed JSON. Carries the byte offset so a rule author can find the typo. */
public class JsonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
        super(message);
    }

    public JsonException(String message, Throwable cause) {
        super(message, cause);
    }
}
