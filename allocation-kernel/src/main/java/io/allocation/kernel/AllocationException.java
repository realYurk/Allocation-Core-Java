package io.allocation.kernel;

/**
 * Base type for every failure the kernel raises deliberately.
 *
 * <p>CONSTITUTION Art.8.1: an error must carry enough context to locate the money it refers to.
 * {@code stageId}, {@code allocationId} and {@code poolKey} are therefore first-class fields, not
 * message fragments — a caller must never have to parse a string to find out which pool broke.
 */
public class AllocationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final String stageId;
    private final String allocationId;
    private final String poolKey;
    private final String detail;

    public AllocationException(ErrorCode code, String message) {
        this(code, message, null, null, null, null);
    }

    public AllocationException(ErrorCode code, String message, Throwable cause) {
        this(code, message, null, null, null, cause);
    }

    public AllocationException(ErrorCode code, String message,
                               String stageId, String allocationId, String poolKey, Throwable cause) {
        super(buildMessage(message, stageId, allocationId, poolKey), cause);
        this.code = code;
        this.detail = message;
        this.stageId = stageId;
        this.allocationId = allocationId;
        this.poolKey = poolKey;
    }

    private static String buildMessage(String message, String stageId, String allocationId, String poolKey) {
        StringBuilder sb = new StringBuilder(message);
        if (stageId != null) {
            sb.append(" [stage=").append(stageId).append(']');
        }
        if (allocationId != null) {
            sb.append(" [rule=").append(allocationId).append(']');
        }
        if (poolKey != null) {
            sb.append(" [pool=").append(poolKey).append(']');
        }
        return sb.toString();
    }

    public ErrorCode code() {
        return code;
    }

    /** The raw message without the context suffix. */
    public String detail() {
        return detail;
    }

    public String stageId() {
        return stageId;
    }

    public String allocationId() {
        return allocationId;
    }

    public String poolKey() {
        return poolKey;
    }

    /** Returns a copy carrying the given context, when the throw site did not know it. */
    public AllocationException withContext(String stage, String rule, String pool) {
        return new AllocationException(code, detail, stage, rule, pool, this);
    }
}
