package io.allocation.spring.web;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.reconcile.Reconciler;
import io.allocation.kernel.service.AllocationTemplate;
import io.allocation.kernel.service.AmountSource;
import io.allocation.kernel.service.PreviewCommand;
import io.allocation.kernel.service.ReconcileCommand;
import io.allocation.kernel.service.RunCommand;
import io.allocation.kernel.service.RunView;
import io.allocation.spring.AllocationProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The REST surface of {@code docs/API.md} §2, and nothing beyond it.
 *
 * <p>Three rules make the rest of this class short:
 *
 * <ul>
 *   <li><b>The controller does not decide anything.</b> Admissibility limits, idempotency, ordering and
 *       failure are the kernel's, so a caller over HTTP and a caller in-process get the same answers to
 *       the same requests. The only checks here are the ones the kernel cannot make because it does not
 *       know it is behind a wire — paging bounds, and the shape of a JSON body.</li>
 *   <li><b>The controller never maps a failure to a status code.</b> That is
 *       {@link AllocationExceptionHandler}'s single job, so the code-to-HTTP table exists once.</li>
 *   <li><b>The controller holds no state.</b> It can be instantiated per request, shared, or proxied;
 *       everything mutable lives in the engine.</li>
 * </ul>
 */
@RestController
@RequestMapping("/allocation")
public class AllocationController {

    /** Largest page a caller may ask for. Above this the answer costs more to serialize than it informs. */
    static final int MAX_PAGE_SIZE = 5_000;

    private final AllocationTemplate template;
    private final AllocationProperties properties;

    public AllocationController(AllocationTemplate template, AllocationProperties properties) {
        this.template = template;
        this.properties = properties;
    }

    // ---------------------------------------------------------------- preview

    @PostMapping("/preview")
    public ApiResponse<Api.PreviewResponse> preview(@RequestBody Api.PreviewRequest request) {
        PreviewCommand command = PreviewCommand.builder()
                .ruleCode(request.ruleCode())
                .rows(ApiMapper.rows(request.rows()))
                .explain(request.explain())
                .build();
        return ApiResponse.ok(ApiMapper.preview(template.preview(command)));
    }

    // ---------------------------------------------------------------- runs

    /**
     * Accepts a run for asynchronous execution.
     *
     * <p>{@code 202} rather than {@code 201}: a run exists and has an id, but the thing that was created
     * is a promise of work, and a client that treats {@code 201} as "the resource is ready to read" would
     * be misled by it.
     */
    @PostMapping("/runs")
    public ResponseEntity<ApiResponse<Api.SubmitResponse>> submit(@RequestBody Api.SubmitRequest request) {
        AllocationTemplate.SubmitResult result = template.submit(command(request));
        return ResponseEntity.status(202).body(ApiResponse.accepted(ApiMapper.submitted(result)));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<Api.StatusResponse> status(@PathVariable("runId") String runId) {
        return ApiResponse.ok(ApiMapper.status(template.status(runId)));
    }

    @GetMapping("/runs/{runId}/result")
    public ApiResponse<Api.ResultResponse> result(@PathVariable("runId") String runId,
                                                  @RequestParam(name = "page", defaultValue = "1") int page,
                                                  @RequestParam(name = "size", defaultValue = "500") int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new AllocationException(ErrorCode.REQUEST_TOO_LARGE,
                    "size must be 1.." + MAX_PAGE_SIZE + ", got " + size);
        }
        return ApiResponse.ok(ApiMapper.result(template.result(runId, page, size)));
    }

    @PostMapping("/runs/{runId}/reconcile")
    public ApiResponse<Api.ReconcileResponse> reconcile(@PathVariable("runId") String runId,
                                                        @RequestBody Api.ReconcileRequest request) {
        if (request.reference() == null || request.reference().path() == null) {
            throw new AllocationException(ErrorCode.RULE_INVALID, "reference.path is required");
        }
        String type = request.reference().type();
        if (type != null && !type.isBlank() && !"csv".equalsIgnoreCase(type)) {
            throw new AllocationException(ErrorCode.RULE_INVALID,
                    "reference.type " + type + " is not supported; only csv exists");
        }
        ReconcileCommand command = ReconcileCommand.builder()
                .runId(runId)
                .reference(Path.of(request.reference().path()))
                .tolerance(request.tolerance())
                .duplicatePolicy(policy(request.duplicatePolicy()))
                .build();
        return ApiResponse.ok(ApiMapper.reconcile(template.reconcile(command)));
    }

    /**
     * The decision chain for one coordinate, with the coordinate carried in the query string.
     *
     * <p>Query parameters rather than a body because this is a read: it must be cacheable, linkable and
     * pasteable into a ticket, and {@code GET} is the verb that makes those three true.
     */
    @GetMapping("/runs/{runId}/explain")
    public ApiResponse<Api.ExplainResponse> explain(@PathVariable("runId") String runId,
                                                    @RequestParam Map<String, String> coord) {
        Map<String, String> requested = new LinkedHashMap<>(coord);
        requested.remove("page");
        requested.remove("size");
        if (requested.isEmpty()) {
            throw new AllocationException(ErrorCode.RULE_INVALID,
                    "explain needs the coordinate as query parameters, one per dimension");
        }
        return ApiResponse.ok(ApiMapper.explain(template.explain(runId, requested)));
    }

    @PostMapping("/runs/{runId}/cancel")
    public ApiResponse<Api.CancelResponse> cancel(@PathVariable("runId") String runId) {
        // Status first: it is the only call that can distinguish "unknown run" (an error) from "nothing
        // left to cancel" (an answer), and it must run before the stop order changes anything it reports.
        RunView before = template.status(runId);
        boolean accepted = template.cancel(runId);
        return ApiResponse.ok(ApiMapper.cancel(runId, accepted, before));
    }

    // ---------------------------------------------------------------- mapping

    /**
     * Builds the run command.
     *
     * <p>Options left out take the deployment defaults from {@link AllocationProperties}, not the
     * kernel's: the host configured this deployment, and a per-run override is an exception to that
     * configuration rather than a replacement for it.
     */
    private RunCommand command(Api.SubmitRequest request) {
        if (request.source() == null) {
            throw new AllocationException(ErrorCode.RULE_INVALID, "source is required");
        }
        Api.RunOptionsRequest options = request.options();
        int workers = options == null || options.workers() == null ? properties.getWorkers() : options.workers();
        Boolean retain = options == null ? null : options.retainLines();
        boolean failOnMissing = options != null && Boolean.TRUE.equals(options.failOnMissingTarget());
        long maxRows = options == null || options.maxRowsInMemory() == null
                ? properties.getMaxRowsInMemory() : options.maxRowsInMemory();

        return RunCommand.builder()
                .ruleCode(request.ruleCode())
                .source(source(request.source()))
                .idempotencyKey(request.idempotencyKey())
                .options(new RunCommand.Options(workers, retain == null || retain, failOnMissing, maxRows))
                .build();
    }

    private static AmountSource source(Api.SourceRequest source) {
        String type = source.type() == null ? "" : source.type().toLowerCase();
        return switch (type) {
            case "csv" -> {
                if (source.path() == null || source.path().isBlank()) {
                    throw new AllocationException(ErrorCode.RULE_INVALID, "source.path is required for csv");
                }
                yield new AmountSource.Csv(Path.of(source.path()));
            }
            case "memory" -> {
                if (source.rows() == null || source.rows().isEmpty()) {
                    throw new AllocationException(ErrorCode.RULE_INVALID,
                            "source.rows is required for memory");
                }
                yield new AmountSource.InMemory(ApiMapper.rows(source.rows()));
            }
            default -> throw new AllocationException(ErrorCode.RULE_INVALID,
                    "source.type must be csv or memory, got " + source.type());
        };
    }

    private static Reconciler.DuplicatePolicy policy(String text) {
        if (text == null || text.isBlank()) {
            return Reconciler.DuplicatePolicy.AGGREGATE;
        }
        try {
            return Reconciler.DuplicatePolicy.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AllocationException(ErrorCode.RULE_INVALID, "duplicatePolicy " + text
                    + " is not one of AGGREGATE, REJECT, REJECT_ACTUAL, REJECT_REFERENCE");
        }
    }
}
