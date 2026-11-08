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
     * Coarse progress, scoped to one stage.
     *
     * <p>{@code stageId} is the stage the two pool counters belong to. It is {@code null} when the numbers
     * describe the whole run, which is what a finished run reports: its pools come from committed
     * evidence, so there is no "current" stage to name.
     *
     * <p>The scope field exists because without it these numbers move backwards. A two-stage rule reports
     * {@code 4/4} at the end of the first stage and {@code 0/12} at the start of the second, so a progress
     * bar built on {@code pools / totalPools} goes from complete to zero while the run has only gained
     * progress. The counters were not wrong; the word they belong to was missing.
     *
     * @param stageId    stage these counters describe, or {@code null} for the run as a whole
     * @param pools      pools completed in that scope
     * @param totalPools pools in that scope, known before the stage starts so a denominator always exists
     * @param rows       rows written so far in that scope
     */
    public record Progress(String stageId, int pools, int totalPools, long rows) {
    }
}
