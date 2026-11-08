package io.allocation.spring.web;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns every failure into the envelope, and the code-to-status table into one table.
 *
 * <p>CONSTITUTION Art.8.1 is the reason this class exists rather than a {@code try/catch} in each
 * handler: an error must carry enough context to locate the money it refers to, and a mapping written
 * ten times is a mapping that is wrong in one of them. {@code stageId}, {@code allocationId} and
 * {@code poolKey} are therefore lifted out of the exception into the response body whenever the throw
 * site knew them.
 *
 * <p>The table is the one in {@code docs/API.md} §3, including the choice that {@code ALLOC_006} (result
 * of a run that is not published) is {@code 409} and not {@code 404}: the resource is where the caller
 * says it is, it is simply not in the state the request requires, and retrying later is the correct
 * response to it.
 *
 * <p>{@code ALLOC_009} adds one thing the other codes do not: the trace id is already in the envelope of
 * every response, so an incident report can be matched to a log line without the class having to log.
 */
@RestControllerAdvice
public class AllocationExceptionHandler {

    @ExceptionHandler(AllocationException.class)
    public ResponseEntity<ApiResponse<Object>> handle(AllocationException e) {
        ErrorCode code = e.code();
        HttpStatus status = statusOf(code);
        Map<String, String> context = new LinkedHashMap<>();
        boolean any = false;
        if (e.stageId() != null) {
            context.put("stageId", e.stageId());
            any = true;
        }
        if (e.allocationId() != null) {
            context.put("allocationId", e.allocationId());
            any = true;
        }
        if (e.poolKey() != null) {
            context.put("poolKey", e.poolKey());
            any = true;
        }
        return ResponseEntity.status(status)
                .body(ApiResponse.error(status.value(), code.code(), e.detail(), any ? context : null));
    }

    /**
     * A request that is syntactically fine but not decodable into the contract.
     *
     * <p>Mapped to {@code ALLOC_001} rather than to a new code: the taxonomy is closed (Art.15), and a
     * caller that cannot form a valid request is exactly the class of caller a rule-validation failure
     * describes. The message says what could not be read, which is what the caller actually needs.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiResponse<Object>> handleBadRequest(Exception e) {
        String message = e instanceof HttpMessageNotReadableException
                ? "request body could not be read: " + rootMessage(e)
                : rootMessage(e);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, ErrorCode.RULE_INVALID.code(), message, null));
    }

    /**
     * Anything else.
     *
     * <p>The exception is not echoed: an internal failure's message is written for whoever will debug it,
     * and sending it to a caller leaks the shape of the system to anyone who can cause an error. The
     * trace id is the contract — it is what ties a user's report to the stack trace in the log.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnexpected(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, ErrorCode.INTERNAL.code(),
                        "internal error; quote the traceId when reporting it", null));
    }

    /** The published table. Exposed so a test can assert it covers every code. */
    public static HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case RULE_INVALID, AMOUNT_OUT_OF_RANGE -> HttpStatus.BAD_REQUEST;
            case CONSERVATION_FAILED, NO_ELIGIBLE_DRIVERS -> HttpStatus.UNPROCESSABLE_ENTITY;
            case IDEMPOTENCY_CONFLICT, RESULT_NOT_PUBLISHED, CANCELLED, ABANDONED -> HttpStatus.CONFLICT;
            case REQUEST_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }
}
