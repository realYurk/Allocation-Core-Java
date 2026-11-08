package io.allocation.kernel.service;

import io.allocation.kernel.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closing runs whose process is gone.
 *
 * <p>The failure this prevents is not a wrong number; it is a record that stays {@code RUNNING} for ever,
 * so every caller who polls it waits for something nobody is doing. Nothing in the engine can notice —
 * the thread that would have written the failure is the one that died — so it has to be a sweep with an
 * explicit cutoff.
 */
class RunRecoveryTest {

    private static final long BOOT = 1_700_000_000_000L;

    private final InMemoryRunRepository repository = new InMemoryRunRepository();

    private void insert(String runId, RunRepository.Status status, long startedAt) {
        repository.insert(new RunRepository.RunRecord(runId, "rule", "sha", null, "req", 2, status,
                0L, 0L, 0, 0L, null, null, startedAt, null));
    }

    @Test
    void marksRunsStrandedBeforeTheCutoffAsAbandoned() {
        insert("01RUNNING", RunRepository.Status.RUNNING, BOOT - 60_000L);
        insert("01QUEUED", RunRepository.Status.PENDING, BOOT - 30_000L);

        assertEquals(2, RunRecovery.abandonInterrupted(repository, BOOT));

        for (String runId : new String[]{"01RUNNING", "01QUEUED"}) {
            RunRepository.RunRecord record = repository.find(runId).orElseThrow();
            assertEquals(RunRepository.Status.FAILED, record.status());
            assertEquals(ErrorCode.ABANDONED.code(), record.failureCode());
            assertTrue(record.failureMessage().contains("resubmit"), record.failureMessage());
        }
    }

    @Test
    void leavesRunsStartedAfterTheCutoffAlone() {
        insert("01LIVE", RunRepository.Status.RUNNING, BOOT + 1L);

        assertEquals(0, RunRecovery.abandonInterrupted(repository, BOOT));
        assertEquals(RunRepository.Status.RUNNING, repository.find("01LIVE").orElseThrow().status());
    }

    @Test
    void leavesFinishedRunsAlone() {
        insert("01DONE", RunRepository.Status.PUBLISHED, BOOT - 60_000L);
        insert("01CANCELLED", RunRepository.Status.CANCELLED, BOOT - 60_000L);
        insert("01FAILED", RunRepository.Status.FAILED, BOOT - 60_000L);

        assertEquals(0, RunRecovery.abandonInterrupted(repository, BOOT));
        assertEquals(RunRepository.Status.PUBLISHED, repository.find("01DONE").orElseThrow().status());
    }

    @Test
    void aRunThatFinishedDuringTheSweepIsNotCounted() {
        // The record says RUNNING, but the update guard only rewrites a row that is still PENDING or
        // RUNNING. A repository that wins the race must not be reported as abandoned: the count is a
        // statement about what happened, not about what was attempted.
        repository.insert(new RunRepository.RunRecord("01RACY", "rule", "sha", null, "req", 2,
                RunRepository.Status.RUNNING, 0L, 0L, 0, 0L, null, null, BOOT - 60_000L, null));

        RunRepository racing = new Delegating(repository) {
            @Override
            public void update(RunRepository.RunRecord run) {
                // The other process finishes it first; nothing is applied.
            }
        };

        assertEquals(0, RunRecovery.abandonInterrupted(racing, BOOT));
        assertEquals(RunRepository.Status.RUNNING, repository.find("01RACY").orElseThrow().status());
    }

    @Test
    void rejectsAnImpossibleLimit() {
        assertThrows(IllegalArgumentException.class, () -> RunRecovery.abandonInterrupted(repository, BOOT, 0));
    }

    /** Delegating repository so one behaviour can be swapped without a mocking framework. */
    private static class Delegating implements RunRepository {

        private final RunRepository delegate;

        Delegating(RunRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public EvidenceWriter openEvidence(String runId) {
            return delegate.openEvidence(runId);
        }

        @Override
        public java.util.Optional<RunRecord> find(String runId) {
            return delegate.find(runId);
        }

        @Override
        public java.util.Optional<RunRecord> findByIdempotencyKey(String idempotencyKey) {
            return delegate.findByIdempotencyKey(idempotencyKey);
        }

        @Override
        public java.util.List<RunRecord> byStatus(Status status, int limit) {
            return delegate.byStatus(status, limit);
        }

        @Override
        public void insert(RunRecord run) {
            delegate.insert(run);
        }

        @Override
        public void update(RunRecord run) {
            delegate.update(run);
        }

        @Override
        public void savePools(String runId, java.util.List<PoolRecord> pools) {
            delegate.savePools(runId, pools);
        }

        @Override
        public void saveLines(String runId, java.util.List<io.allocation.kernel.exec.RunSink.LedgerLine> lines) {
            delegate.saveLines(runId, lines);
        }

        @Override
        public void saveResults(String runId,
                                java.util.List<io.allocation.kernel.exec.RunSink.ResultLine> results) {
            delegate.saveResults(runId, results);
        }

        @Override
        public java.util.List<PoolRecord> pools(String runId) {
            return delegate.pools(runId);
        }

        @Override
        public java.util.List<io.allocation.kernel.exec.RunSink.LedgerLine> lines(String runId) {
            return delegate.lines(runId);
        }

        @Override
        public java.util.List<io.allocation.kernel.exec.RunSink.ResultLine> results(String runId, int offset,
                                                                                    int limit) {
            return delegate.results(runId, offset, limit);
        }

        @Override
        public int resultCount(String runId) {
            return delegate.resultCount(runId);
        }
    }
}
