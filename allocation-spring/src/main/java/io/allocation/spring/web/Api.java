package io.allocation.spring.web;

import java.util.List;
import java.util.Map;

/**
 * Wire types for the REST contract.
 *
 * <p>These are deliberately <em>not</em> the kernel's types. The contract is a published document, and a
 * published document must not change because someone renamed a field in the engine. Money travels as a
 * decimal string, never as a floating-point number: a JSON number with a fractional part is a promise to
 * lose precision that no amount of care at the encoder can keep.
 */
public final class Api {

    private Api() {
    }

    // ---------------------------------------------------------------- preview

    /** One input row: a full coordinate and an amount literal. */
    public record RowRequest(Map<String, String> coord, String amount) {
    }

    /** Synchronous what-if. */
    public record PreviewRequest(String ruleCode, List<RowRequest> rows, boolean explain) {
    }

    /** Why one coordinate received what it received. */
    public record TraceResponse(String poolAmount, long weight, long denominator, int remainderRank,
                                boolean gotResidual) {
    }

    /** One allocated coordinate. */
    public record LineResponse(Map<String, String> coord, String amount, TraceResponse trace) {
    }

    /** Whole-preview balance. */
    public record ConservationResponse(String input, String output, boolean balanced) {
    }

    /** A non-fatal observation from the engine. */
    public record WarningResponse(String code, String message, String stageId, String allocationId) {
    }

    /** Preview result. */
    public record PreviewResponse(List<LineResponse> lines, ConservationResponse conservation,
                                  List<WarningResponse> warnings) {
    }

    // ---------------------------------------------------------------- runs

    /** Where the amounts come from. {@code type} is {@code csv} or {@code memory}. */
    public record SourceRequest(String type, String path, List<RowRequest> rows) {
    }

    /** Per-run overrides. Any field left out takes the deployment default. */
    public record RunOptionsRequest(Integer workers, Boolean retainLines, Boolean failOnMissingTarget,
                                    Long maxRowsInMemory) {
    }

    /** Submit a full run. */
    public record SubmitRequest(String ruleCode, SourceRequest source, String idempotencyKey,
                                RunOptionsRequest options) {
    }

    /** Submission receipt. */
    public record SubmitResponse(String runId, String status, boolean idempotentReplay) {
    }

    /** Coarse progress. */
    public record ProgressResponse(int pools, int totalPools, long rows) {
    }

    /** Poll one run. */
    public record StatusResponse(String runId, String status, ProgressResponse progress, String startedAt,
                                 long elapsedMs) {
    }

    /** One page of published results. */
    public record ResultResponse(String runId, String status, int page, int size, int total,
                                 List<LineResponse> lines, ConservationResponse conservation) {
    }

    /** One step of an explanation. */
    public record StepResponse(String stageId, String ruleId, String poolKey, String poolAmount,
                               String shareAmount, long weight, long denominator, int remainderRank,
                               boolean gotResidual, String allocatedAmount) {
    }

    /** The full decision chain for one coordinate. */
    public record ExplainResponse(String runId, Map<String, String> coord, String ruleSha256,
                                  List<StepResponse> steps) {
    }

    // ---------------------------------------------------------------- reconcile

    /** The independent answer. Only {@code csv} exists today. */
    public record ReferenceRequest(String type, String path) {
    }

    /** Compare a published run against a reference file. */
    public record ReconcileRequest(ReferenceRequest reference, String tolerance, String duplicatePolicy) {
    }

    /** One coordinate where the two sides disagree. */
    public record DifferenceResponse(String kind, String account, String coordinate, String referenceAmount,
                                     String actualAmount, String difference) {
    }

    /** Outcome of a comparison. */
    public record ReconcileResponse(String status, long matched, long amountDiffered, long missing, long extra,
                                    String actualTotal, String referenceTotal, String referenceSha256,
                                    List<DifferenceResponse> top, String differenceFile) {
    }

    // ---------------------------------------------------------------- cancel

    /** Result of a stop order. */
    public record CancelResponse(String runId, boolean accepted, String status) {
    }
}
