package io.allocation.kernel.service;

import io.allocation.kernel.ErrorCode;

import java.util.List;
import java.util.Optional;

/**
 * Closes runs whose process is gone.
 *
 * <p>A run is created before it executes and updated exactly once to a terminal state. That is the right
 * ordering — a crashed process then leaves a visibly unfinished run instead of a missing one — but it
 * leaves the record saying {@code RUNNING} forever, and a caller polling it has no way to learn that
 * nobody is working on it. Nothing inside the engine can fix that: the thread that would have written the
 * failure is the one that died.
 *
 * <h2>Why this takes a cutoff rather than deciding for itself</h2>
 * The only thing this process can know for certain is that <em>it</em> is not running a given run. In a
 * single-instance deployment "started before this process booted" is therefore a proof of abandonment. In
 * a multi-instance deployment it is not: another instance may have started that run a minute before this
 * one, and it is still working. So the cutoff is a parameter, and the caller is the only party that knows
 * which of the two situations it is in. A convenience that guessed would sometimes kill a live run's
 * record, and there is no undo for that.
 *
 * <h2>Why the count is checked, not assumed</h2>
 * {@link RunRepository#update} applies a terminal transition only while the row is still
 * {@code PENDING}/{@code RUNNING} (D-015). A run that finished between the query and the update is
 * therefore untouched, and reporting it as abandoned would be a lie about someone else's successful work.
 * Each candidate is re-read and counted only if it now carries this method's own failure code.
 */
public final class RunRecovery {

    /** How many candidates one call will look at. A backlog can be worked through by calling again. */
    public static final int DEFAULT_LIMIT = 1_000;

    static final String MESSAGE =
            "abandoned: the process that owned this run stopped before it finished; resubmit to compute it";

    private RunRecovery() {
    }

    /**
     * Marks runs that started before the cutoff, and have not finished, as abandoned.
     *
     * <p>Both non-terminal states are swept. A {@code RUNNING} row means the process died mid-run; a
     * {@code PENDING} row means it died with the run still queued. Nothing will ever pick either up —
     * dispatch is a virtual thread in the process that accepted them — so both are equally stuck, and
     * treating {@code PENDING} as "about to start" would leave the more common of the two behind.
     *
     * @param repository          store holding the runs
     * @param startedBeforeEpochMs a run must have started strictly before this instant to qualify;
     *                             pass this process's own boot time in a single-instance deployment
     * @param limit               candidates to examine per state
     * @return how many runs were actually transitioned
     */
    public static int abandonInterrupted(RunRepository repository, long startedBeforeEpochMs, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        int abandoned = 0;
        for (RunRepository.Status status : List.of(RunRepository.Status.RUNNING, RunRepository.Status.PENDING)) {
            for (RunRepository.RunRecord record : repository.byStatus(status, limit)) {
                if (record.startedAtEpochMs() >= startedBeforeEpochMs) {
                    continue;
                }
                repository.update(record.failed(ErrorCode.ABANDONED.code(), MESSAGE,
                        System.currentTimeMillis()));
                if (wasAbandoned(repository.find(record.runId()))) {
                    abandoned++;
                }
            }
        }
        return abandoned;
    }

    /** Convenience with {@link #DEFAULT_LIMIT}. */
    public static int abandonInterrupted(RunRepository repository, long startedBeforeEpochMs) {
        return abandonInterrupted(repository, startedBeforeEpochMs, DEFAULT_LIMIT);
    }

    private static boolean wasAbandoned(Optional<RunRepository.RunRecord> record) {
        return record.isPresent()
                && record.get().status() == RunRepository.Status.FAILED
                && ErrorCode.ABANDONED.code().equals(record.get().failureCode());
    }
}
