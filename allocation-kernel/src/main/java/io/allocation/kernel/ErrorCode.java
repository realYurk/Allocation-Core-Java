package io.allocation.kernel;

/**
 * Stable error taxonomy. Codes are part of the public contract and never change meaning.
 *
 * <p>CONSTITUTION Art.15: adding a code requires updating {@code docs/API.md} in the same commit.
 */
public enum ErrorCode {

    /** Rule is structurally invalid: schema, dimension, stage or share validation failed. */
    RULE_INVALID("ALLOC_001"),

    /** A decimal literal could not be parsed exactly, or left the int64 range. */
    AMOUNT_OUT_OF_RANGE("ALLOC_002"),

    /** A pool or the grand total did not balance. Always fatal. */
    CONSERVATION_FAILED("ALLOC_003"),

    /** A pool produced zero eligible targets. Always fatal. */
    NO_ELIGIBLE_DRIVERS("ALLOC_004"),

    /** Idempotency key reused with a different rule or input fingerprint. */
    IDEMPOTENCY_CONFLICT("ALLOC_005"),

    /** Result requested for a run that is not PUBLISHED. */
    RESULT_NOT_PUBLISHED("ALLOC_006"),

    /** Request exceeds a configured size limit. */
    REQUEST_TOO_LARGE("ALLOC_007"),

    /** Run exceeded its deadline. */
    TIMEOUT("ALLOC_008"),

    /** Unclassified internal failure. Always carries a trace id. */
    INTERNAL("ALLOC_009"),

    /** Authorizer rejected the caller. */
    FORBIDDEN("ALLOC_010"),

    /** The caller cancelled the run, or the run was cancelled before it finished. Not a failure. */
    CANCELLED("ALLOC_011"),

    /**
     * The process that owned the run stopped before the run finished.
     *
     * <p>Recorded as a failure because it is one: the answer does not exist and the work has to be
     * resubmitted. The code exists so an operator can tell "we lost the process" from "the data was
     * wrong" without reading a message.
     */
    ABANDONED("ALLOC_012"),

    /**
     * An idempotency key was used with a custom input that cannot say what data it read.
     *
     * <p>Refusing is the only honest answer: the key promises "same key, same data", and an input without
     * a fingerprint cannot keep that promise, so the alternative is returning a previous run's numbers for
     * a table that has since changed. Hosts that need a key implement {@code fingerprint}; hosts that do
     * not need a key do not implement anything.
     */
    IDEMPOTENCY_UNVERIFIABLE("ALLOC_013"),

    /**
     * No run with that id exists — or it belongs to someone else.
     *
     * <p>The two are one code on purpose. A caller who is told "not yours" learns that the id is real,
     * and a run id is the only thing between a stranger and a company's cost allocation; confirming it
     * exists is a leak. Answering {@code 404} for both is also the only answer that is true for a
     * mistyped id, which is the common case.
     */
    RUN_NOT_FOUND("ALLOC_014");

    private final String code;

    ErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
