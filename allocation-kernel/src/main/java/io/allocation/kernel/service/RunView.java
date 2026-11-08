package io.allocation.kernel.service;

/**
 * The state of a run, as a poller sees it.
 *
 * @param startedAtEpochMs wall clock at submission
 * @param elapsedMs        time since submission
 */
public record RunView(String runId, RunRepository.Status status, Progress progress,
                      long startedAtEpochMs, long elapsedMs) {

    /**
     * Coarse progress.
     *
     * @param pools      pools completed so far
     * @param totalPools total pools in the plan, known before execution starts
     */
    public record Progress(int pools, int totalPools, long rows) {
    }
}
