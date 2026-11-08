package io.allocation.kernel.service;

import io.allocation.kernel.exec.RunSink;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for runs and their evidence.
 *
 * <p>Deliberately narrow: the kernel does not know whether the rows live in MySQL, in a file, or in a
 * map. What it does insist on is that a run is created before it executes and updated exactly once to a
 * terminal status, so a crashed process leaves a visibly unfinished run instead of a missing one.
 *
 * <p>Idempotency is a unique key on {@code idempotencyKey}, not a cache. Two concurrent submissions
 * with the same key must resolve to one run; the second one loses the insert and reads the winner.
 */
public interface RunRepository {

    /** Lifecycle states. Only the terminal ones may never change again. */
    enum Status {
        PENDING,
        RUNNING,
        PUBLISHED,
        FAILED,
        CANCELLED;

        public boolean terminal() {
            return this == PUBLISHED || this == FAILED || this == CANCELLED;
        }
    }

    /**
     * One run.
     *
     * @param requestSha256 digest of rule plus input, used to detect an idempotency conflict
     * @param precision     decimal places the amounts in this run are scaled to; an amount without it
     *                      is an integer of unknown magnitude, so it is stored with the run rather than
     *                      re-derived from whatever rule happens to be current when someone reads it
     */
    record RunRecord(String runId, String ruleCode, String ruleSha256, String idempotencyKey,
                     String requestSha256, int precision, Status status, long inputAmount, long outputAmount,
                     int poolCount, long rowCount, String failureCode, String failureMessage,
                     long startedAtEpochMs, Long finishedAtEpochMs) {

        public RunRecord withStatus(Status next) {
            return new RunRecord(runId, ruleCode, ruleSha256, idempotencyKey, requestSha256, precision,
                    next, inputAmount, outputAmount, poolCount, rowCount, failureCode, failureMessage,
                    startedAtEpochMs, finishedAtEpochMs);
        }

        public RunRecord published(long input, long output, int pools, long rows, long finishedAt) {
            return new RunRecord(runId, ruleCode, ruleSha256, idempotencyKey, requestSha256, precision,
                    Status.PUBLISHED, input, output, pools, rows, null, null, startedAtEpochMs, finishedAt);
        }

        public RunRecord failed(String code, String message, long finishedAt) {
            return new RunRecord(runId, ruleCode, ruleSha256, idempotencyKey, requestSha256, precision,
                    Status.FAILED, inputAmount, outputAmount, poolCount, rowCount, code, message,
                    startedAtEpochMs, finishedAt);
        }
    }

    /** One pool's balance evidence. */
    record PoolRecord(String stageId, String poolKey, long inputAmount, long outputAmount, int targetCount) {
    }

    Optional<RunRecord> find(String runId);

    Optional<RunRecord> findByIdempotencyKey(String idempotencyKey);

    /** Inserts a new run; must fail if the id or the idempotency key already exists. */
    void insert(RunRecord run);

    void update(RunRecord run);

    void savePools(String runId, List<PoolRecord> pools);

    void saveLines(String runId, List<RunSink.LedgerLine> lines);

    void saveResults(String runId, List<RunSink.ResultLine> results);

    List<PoolRecord> pools(String runId);

    List<RunSink.LedgerLine> lines(String runId);

    /** Paged results; {@code offset} is zero based. */
    List<RunSink.ResultLine> results(String runId, int offset, int limit);

    int resultCount(String runId);
}
