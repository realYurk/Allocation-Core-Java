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

        /**
         * The run was stopped by its caller.
         *
         * <p>Distinct from {@code FAILED}: nothing went wrong, somebody changed their mind, and the
         * distinction is what keeps an operator dashboard from showing a cancellation as an incident.
         * The failure fields are cleared, since a cancellation has no error to report.
         */
        public RunRecord cancelled(long finishedAt) {
            return new RunRecord(runId, ruleCode, ruleSha256, idempotencyKey, requestSha256, precision,
                    Status.CANCELLED, inputAmount, outputAmount, poolCount, rowCount, null, null,
                    startedAtEpochMs, finishedAt);
        }
    }

    /** One pool's balance evidence. */
    record PoolRecord(String stageId, String poolKey, long inputAmount, long outputAmount, int targetCount) {
    }

    /**
     * Streaming writer for one run's evidence.
     *
     * <p>Exists because a run cannot be required to hold its results in memory until the end: at a
     * million rows that is hundreds of megabytes, and at ten million it does not fit at all. A writer is
     * obtained before execution, fed as rows are produced, and committed once.
     *
     * <p>Two guarantees make this safe to read while a run is in flight:
     *
     * <ul>
     *   <li>The service never reports a run as {@code PUBLISHED} before {@link EvidenceWriter#commit()},
     *       so a reader that respects the status can never observe half a result set.</li>
     *   <li>Closing without committing discards everything written, so a failed run leaves no rows
     *       behind to be mistaken for an answer.</li>
     * </ul>
     */
    interface EvidenceWriter extends AutoCloseable {

        void pool(PoolRecord record);

        void line(RunSink.LedgerLine line);

        void result(RunSink.ResultLine line);

        /** Makes everything written so far durable. Call exactly once, before the run is published. */
        void commit();

        /** Releases resources; discards the evidence when {@link #commit()} was never called. */
        @Override
        void close();
    }

    /** Opens a writer for a run that is about to execute. */
    EvidenceWriter openEvidence(String runId);

    /**
     * The run, if this caller may see it.
     *
     * <p>An implementation that is scoped by {@link TenantProvider} returns empty for a run of another
     * tenant, exactly as for an id that never existed. A host writing its own repository — a MyBatis
     * mapper, say — must do the same, and must do it here rather than in its controllers: this is the
     * only method through which the engine reaches a run, so it is the only place where one filter is
     * enough.
     */
    Optional<RunRecord> find(String runId);

    /**
     * The run previously submitted under this key, within the caller's tenant.
     *
     * <p>Scoped even when there is no tenant context — the empty tenant stands in for it — because a key
     * promises "the same request, not a second one", and that promise is made by one tenant to itself.
     * An unscoped lookup would let a batch job collide with a company's key or, worse, hand it that
     * company's run. {@link #find} answers the other question (may I read this run) and is deliberately
     * broader for a context-less caller, because the startup sweep and an operator have to be able to
     * read every run.
     */
    Optional<RunRecord> findByIdempotencyKey(String idempotencyKey);

    /**
     * Runs in a given state, oldest first, at most {@code limit}.
     *
     * <p>Exists for one question: which runs are still {@code RUNNING} even though nothing is executing
     * them. A crashed process leaves exactly that, and the only way to find them is to ask the database —
     * the process that knew is gone.
     */
    List<RunRecord> byStatus(Status status, int limit);

    /** Inserts a new run; must fail if the id or the idempotency key already exists. */
    void insert(RunRecord run);

    void update(RunRecord run);

    /**
     * Bulk-loads evidence in one call.
     *
     * <p>For importing an artefact produced elsewhere — an archive, a replay, a test. A run uses
     * {@link #openEvidence} instead, because it produces rows one at a time and cannot hold them.
     */
    void savePools(String runId, List<PoolRecord> pools);

    void saveLines(String runId, List<RunSink.LedgerLine> lines);

    void saveResults(String runId, List<RunSink.ResultLine> results);

    List<PoolRecord> pools(String runId);

    List<RunSink.LedgerLine> lines(String runId);

    /**
     * The ledger lines of a few target coordinates, in write order.
     *
     * <p>Exists because a result page needs the decision behind at most {@code size} coordinates, and
     * reading the whole ledger to answer that is a memory limit disguised as a query: a million-row run
     * would materialise a million objects to serve ten lines. The default implementation filters
     * {@link #lines(String)}, which is correct and still loads everything, so an implementation that cares
     * should override it with an indexed query.
     */
    default List<RunSink.LedgerLine> linesForTargets(String runId, java.util.Collection<String> targetKeys) {
        if (targetKeys == null || targetKeys.isEmpty()) {
            return List.of();
        }
        java.util.Set<String> wanted = new java.util.HashSet<>(targetKeys);
        List<RunSink.LedgerLine> out = new java.util.ArrayList<>();
        for (RunSink.LedgerLine line : lines(runId)) {
            if (wanted.contains(line.target())) {
                out.add(line);
            }
        }
        return out;
    }

    /**
     * Every ledger line of one pool — all of its targets, in write order.
     *
     * <p>Separate from {@link #linesForTargets} because a target's own line cannot explain itself: its
     * remainder rank is a property of the whole pool. Reading the pool is what makes an explanation exact
     * without reading the run.
     */
    default List<RunSink.LedgerLine> linesForPool(String runId, String stageId, String ruleId, String poolKey) {
        List<RunSink.LedgerLine> out = new java.util.ArrayList<>();
        for (RunSink.LedgerLine line : lines(runId)) {
            if (line.stage().equals(stageId) && line.rule().equals(ruleId) && line.pool().equals(poolKey)) {
                out.add(line);
            }
        }
        return out;
    }

    /** Paged results; {@code offset} is zero based. */
    List<RunSink.ResultLine> results(String runId, int offset, int limit);

    int resultCount(String runId);
}
