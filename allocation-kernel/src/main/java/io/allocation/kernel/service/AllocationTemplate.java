package io.allocation.kernel.service;

import io.allocation.kernel.reconcile.ShardedReconciler;

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
     * Asks a queued or running run to stop.
     *
     * <p>Cooperative, not preemptive: the run stops at the next pool batch boundary, keeps every pool it
     * already finished in its own accounting, and publishes nothing. Returns {@code false} when there is
     * nothing to cancel — the run has already reached a terminal state, is unknown, or is not owned by
     * this process (a run left {@code RUNNING} by a previous process cannot be stopped from here, and
     * saying otherwise would be a promise this call cannot keep).
     *
     * @return {@code true} if the stop order was accepted, meaning the run will end {@code CANCELLED}
     */
    boolean cancel(String runId);

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
     * @param summary         per-coordinate outcome, totals and the SHA-256 of both sides
     * @param differencesPath where the difference file was written; the file is empty when the sides match
     */
    record ReconcileOutcome(ShardedReconciler.Summary summary, String differencesPath) {
    }
}
