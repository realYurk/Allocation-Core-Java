package io.allocation.kernel.service;

import io.allocation.kernel.reconcile.Reconciler;

import java.time.Duration;
import java.util.Map;

/**
 * What a caller can ask the engine to do.
 *
 * <p>The interface exists so a host can substitute a decorator — authorisation, metrics, a dry-run
 * switch — without subclassing the implementation. Everything here is expressed in already-scaled
 * amounts and rule codes; there is no file path or SQL in the contract.
 */
public interface AllocationTemplate {

    /** Computes the result for a few thousand rows without persisting anything. */
    Preview preview(PreviewCommand command);

    /** Queues a full run and returns immediately. */
    SubmitResult submit(RunCommand command);

    RunView status(String runId);

    /** Waits for a run to finish, up to the given timeout. */
    RunResult await(String runId, Duration timeout);

    /** One page of published results. */
    RunResult result(String runId, int page, int size);

    /** The decision chain for one coordinate. */
    Explain explain(String runId, Map<String, String> coord);

    /** Compares a published run against an independent reference file. */
    ReconcileOutcome reconcile(ReconcileCommand command);

    /**
     * Submission receipt.
     *
     * @param idempotentReplay true when the key had already been used and nothing was recomputed
     */
    record SubmitResult(String runId, RunRepository.Status status, boolean idempotentReplay) {
    }

    /**
     * Result of a comparison.
     *
     * @param report          per-coordinate outcome
     * @param differencesPath where the difference file was written, or {@code null}
     */
    record ReconcileOutcome(Reconciler.Report report, String differencesPath) {
    }
}
