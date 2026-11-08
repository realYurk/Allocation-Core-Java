package io.allocation.kernel.service;

import io.allocation.kernel.exec.RunSink;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A repository that parks a run at a chosen point, so a test can observe the run mid-flight.
 *
 * <p>Testing a running thing by racing it produces a test that passes on a fast machine and fails in CI.
 * The alternative used here is to make the run wait: the first evidence line of the Nth pool blocks in
 * the writer until the test releases it. At that moment the executor is provably inside pool N, which
 * makes "what does status report while a run is executing" a question with a determinate answer.
 *
 * <p>A decorator rather than a subclass, because {@link InMemoryRunRepository} is {@code final} on
 * purpose: a storage implementation that expects to be extended is one whose invariants can be broken
 * from outside.
 */
final class BlockingRepository implements RunRepository {

    private final InMemoryRunRepository delegate = new InMemoryRunRepository();
    private final Set<String> seenPools = new HashSet<>();
    private final int blockOnPool;
    private final CountDownLatch reached;
    private final CountDownLatch released = new CountDownLatch(1);

    private BlockingRepository(int blockOnPool) {
        this.blockOnPool = blockOnPool;
        this.reached = new CountDownLatch(1);
    }

    /** Blocks the first evidence line of the given 1-based pool, counting pools in stage order. */
    static BlockingRepository blockOnPool(int oneBasedPool) {
        if (oneBasedPool < 1) {
            throw new IllegalArgumentException("pool index is 1-based");
        }
        return new BlockingRepository(oneBasedPool);
    }

    /** Waits until the run has reached the chosen pool. */
    boolean awaitReached() {
        try {
            return reached.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Lets the run continue. */
    void release() {
        released.countDown();
    }

    @Override
    public EvidenceWriter openEvidence(String runId) {
        EvidenceWriter inner = delegate.openEvidence(runId);
        return new EvidenceWriter() {
            @Override
            public void pool(PoolRecord record) {
                inner.pool(record);
            }

            @Override
            public void line(RunSink.LedgerLine line) {
                // Only the coordinator thread writes evidence (RunSink guarantees it), so the set needs
                // no synchronisation even though the test thread reads the latch.
                if (seenPools.add(line.stage() + '\u0000' + line.pool()) && seenPools.size() == blockOnPool) {
                    reached.countDown();
                    awaitRelease();
                }
                inner.line(line);
            }

            @Override
            public void result(RunSink.ResultLine line) {
                inner.result(line);
            }

            @Override
            public void commit() {
                inner.commit();
            }

            @Override
            public void close() {
                inner.close();
            }
        };
    }

    private void awaitRelease() {
        try {
            released.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Optional<RunRecord> find(String runId) {
        return delegate.find(runId);
    }

    @Override
    public Optional<RunRecord> findByIdempotencyKey(String idempotencyKey) {
        return delegate.findByIdempotencyKey(idempotencyKey);
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
    public void savePools(String runId, List<PoolRecord> pools) {
        delegate.savePools(runId, pools);
    }

    @Override
    public void saveLines(String runId, List<RunSink.LedgerLine> lines) {
        delegate.saveLines(runId, lines);
    }

    @Override
    public void saveResults(String runId, List<RunSink.ResultLine> results) {
        delegate.saveResults(runId, results);
    }

    @Override
    public List<PoolRecord> pools(String runId) {
        return delegate.pools(runId);
    }

    @Override
    public List<RunSink.LedgerLine> lines(String runId) {
        return delegate.lines(runId);
    }

    @Override
    public List<RunSink.ResultLine> results(String runId, int offset, int limit) {
        return delegate.results(runId, offset, limit);
    }

    @Override
    public int resultCount(String runId) {
        return delegate.resultCount(runId);
    }
}
