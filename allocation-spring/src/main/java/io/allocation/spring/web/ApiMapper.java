package io.allocation.spring.web;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.exec.RunSink;
import io.allocation.kernel.money.Decimal;
import io.allocation.kernel.reconcile.ShardedReconciler;
import io.allocation.kernel.service.AllocationTemplate;
import io.allocation.kernel.service.AmountRow;
import io.allocation.kernel.service.Explain;
import io.allocation.kernel.service.Money;
import io.allocation.kernel.service.Preview;
import io.allocation.kernel.service.RunResult;
import io.allocation.kernel.service.RunView;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Kernel types to wire types, and the reverse for the two places a caller sends data.
 *
 * <p>All of it is static and free of Spring: the mapping is the part that must be identical for the
 * REST surface, a future gRPC surface and the tests, so it must not depend on being called from a
 * request thread.
 */
public final class ApiMapper {

    private ApiMapper() {
    }

    // ---------------------------------------------------------------- money

    /**
     * Parses an amount literal at the scale it was written with.
     *
     * <p>The rule's precision is not known yet at this point — it lives inside the rule document, which
     * is read later — so the literal carries its own scale and the kernel widens it. Widening is exact;
     * narrowing is refused, which is what makes {@code 1.5001} against a two-decimal rule an error
     * instead of a rounding.
     *
     * <p>Trailing zeros are dropped first, so {@code 1.5000} at a two-decimal rule is {@code 1.50} rather
     * than a refusal: removing a zero has never changed an amount.
     */
    public static Money money(String literal) {
        if (literal == null || literal.isBlank()) {
            throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE, "amount is required");
        }
        String text = literal.trim();
        int dot = text.indexOf('.');
        if (dot < 0) {
            return Money.parse(text, 0);
        }
        int end = text.length();
        while (end > dot + 1 && text.charAt(end - 1) == '0') {
            end--;
        }
        int scale = end - dot - 1;
        if (scale > Decimal.MAX_SCALE) {
            throw new AllocationException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                    "amount " + text + " has " + scale + " decimals, at most " + Decimal.MAX_SCALE
                            + " are representable");
        }
        // "100.00" is 100 at scale 0, so the point goes with the zeros: leaving it in would make the
        // literal unparseable at the scale that was just chosen for it.
        String normalized = scale == 0 ? text.substring(0, dot) : text.substring(0, end);
        return Money.parse(normalized, scale);
    }

    // ---------------------------------------------------------------- requests

    /** Decodes submitted rows, rejecting a coordinate that is absent rather than letting it fail later. */
    public static List<AmountRow> rows(List<Api.RowRequest> rows) {
        if (rows == null) {
            return List.of();
        }
        List<AmountRow> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Api.RowRequest row = rows.get(i);
            if (row == null || row.coord() == null) {
                throw new AllocationException(ErrorCode.RULE_INVALID, "row " + i + " has no coord");
            }
            Map<String, String> coord = new LinkedHashMap<>(row.coord());
            for (Map.Entry<String, String> entry : coord.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    throw new AllocationException(ErrorCode.RULE_INVALID,
                            "row " + i + " has a null dimension or member");
                }
            }
            out.add(new AmountRow(coord, money(row.amount())));
        }
        return out;
    }

    // ---------------------------------------------------------------- preview

    public static Api.PreviewResponse preview(Preview preview) {
        List<Api.LineResponse> lines = new ArrayList<>(preview.lines().size());
        for (Preview.AllocatedLine line : preview.lines()) {
            lines.add(line(line));
        }
        return new Api.PreviewResponse(lines, conservation(preview.conservation()), warnings(preview.warnings()));
    }

    public static Api.LineResponse line(Preview.AllocatedLine line) {
        return new Api.LineResponse(sorted(line.coord()), line.amount().format(), trace(line.trace()));
    }

    public static Api.TraceResponse trace(Preview.ShareTrace trace) {
        if (trace == null) {
            return null;
        }
        return new Api.TraceResponse(trace.poolAmount().format(), trace.weight(), trace.denominator(),
                trace.remainderRank(), trace.gotResidual());
    }

    public static Api.ConservationResponse conservation(Preview.Conservation conservation) {
        if (conservation == null) {
            return null;
        }
        return new Api.ConservationResponse(conservation.input().format(), conservation.output().format(),
                conservation.balanced());
    }

    public static List<Api.WarningResponse> warnings(List<RunSink.Warning> warnings) {
        List<Api.WarningResponse> out = new ArrayList<>(warnings.size());
        for (RunSink.Warning warning : warnings) {
            out.add(new Api.WarningResponse(warning.code(), warning.message(), warning.stageId(),
                    warning.allocationId()));
        }
        return out;
    }

    // ---------------------------------------------------------------- runs

    public static Api.SubmitResponse submitted(AllocationTemplate.SubmitResult result) {
        return new Api.SubmitResponse(result.runId(), result.status().name(), result.idempotentReplay());
    }

    public static Api.StatusResponse status(RunView view) {
        RunView.Progress progress = view.progress() == null
                ? new RunView.Progress(0, 0, 0L) : view.progress();
        return new Api.StatusResponse(view.runId(), view.status().name(),
                new Api.ProgressResponse(progress.pools(), progress.totalPools(), progress.rows()),
                instant(view.startedAtEpochMs()), view.elapsedMs());
    }

    public static Api.ResultResponse result(RunResult result) {
        List<Api.LineResponse> lines = new ArrayList<>(result.lines().size());
        for (Preview.AllocatedLine line : result.lines()) {
            lines.add(line(line));
        }
        return new Api.ResultResponse(result.runId(), result.status().name(), result.page(), result.size(),
                result.total(), lines, conservation(result.conservation()));
    }

    public static Api.ExplainResponse explain(Explain explain) {
        List<Api.StepResponse> steps = new ArrayList<>(explain.steps().size());
        for (Explain.Step step : explain.steps()) {
            steps.add(new Api.StepResponse(step.stageId(), step.ruleId(), step.poolKey(),
                    step.poolAmount().format(), step.shareAmount().format(), step.weight(), step.denominator(),
                    step.remainderRank(), step.gotResidual(), step.allocatedAmount().format()));
        }
        return new Api.ExplainResponse(explain.runId(), sorted(explain.coord()), explain.ruleSha256(), steps);
    }

    public static Api.CancelResponse cancel(String runId, boolean accepted, RunView view) {
        return new Api.CancelResponse(runId, accepted, view == null ? null : view.status().name());
    }

    // ---------------------------------------------------------------- reconcile

    public static Api.ReconcileResponse reconcile(AllocationTemplate.ReconcileOutcome outcome) {
        ShardedReconciler.Summary summary = outcome.summary();
        List<Api.DifferenceResponse> top = new ArrayList<>(summary.top().size());
        for (ShardedReconciler.Difference difference : summary.top()) {
            top.add(new Api.DifferenceResponse(difference.kind(), difference.account(), difference.coordinate(),
                    difference.referenceAmount(), difference.actualAmount(), difference.delta()));
        }
        return new Api.ReconcileResponse(summary.status(), summary.matched(), summary.differed(),
                summary.missing(), summary.extra(), summary.actualTotal(), summary.referenceTotal(),
                summary.referenceSha256(), top, outcome.differencesPath());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Coordinate in key order.
     *
     * <p>The kernel copies coordinates into an immutable map, whose iteration order is unspecified by
     * design. Emitting that order would make the same answer look different between two runs of the same
     * request, which is the sort of difference that costs somebody an afternoon. Dimension order is a
     * property of the rule; key order is a property of the answer, and stable.
     */
    private static Map<String, String> sorted(Map<String, String> coord) {
        return new TreeMap<>(coord);
    }

    private static String instant(long epochMs) {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs));
    }
}
