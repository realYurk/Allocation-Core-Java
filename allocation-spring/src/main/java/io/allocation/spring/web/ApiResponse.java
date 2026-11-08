package io.allocation.spring.web;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The response envelope every endpoint returns.
 *
 * <p>Shaped to match RuoYi's {@code AjaxResult} — {@code code}, {@code msg}, {@code data} — so a host
 * that already has a front end can consume it without an adapter. Two fields are added, both additive:
 * {@code traceId} so a 500 can be matched to a log line, and {@code errorCode} so a caller can branch on
 * {@code ALLOC_005} instead of parsing a message that is written for a human.
 *
 * @param code      HTTP status, mirroring the transport so a body-only client still sees the outcome
 * @param msg       message for a human; on failure it starts with the error code
 * @param data      payload, or for failures the stage/rule/pool context when it exists
 * @param traceId   correlates this response with the server log
 * @param errorCode {@code ALLOC_0xx} on failure, {@code null} on success
 */
public record ApiResponse<T>(int code, String msg, T data, String traceId, String errorCode) {

    /** A successful response. */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "OK", data, newTraceId(), null);
    }

    /** A response for a request that was accepted and will be completed later. */
    public static <T> ApiResponse<T> accepted(T data) {
        return new ApiResponse<>(202, "Accepted", data, newTraceId(), null);
    }

    /** A failure. {@code context} may be {@code null} when the error has no coordinates attached. */
    public static ApiResponse<Object> error(int status, String errorCode, String message,
                                            Map<String, String> context) {
        String text = errorCode == null ? message : errorCode + " " + message;
        return new ApiResponse<>(status, text, context, newTraceId(), errorCode);
    }

    /**
     * A trace id that is short enough to read aloud in an incident call.
     *
     * <p>No tracing framework is required: a starter that forces one on its host has decided something
     * that is not its business. Hosts that do run one can replace this by wrapping the handler.
     */
    private static String newTraceId() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFFFFFL);
    }
}
