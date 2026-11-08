package io.allocation.kernel.service;

import io.allocation.kernel.exec.RunSink;
import io.allocation.kernel.reconcile.AmountRows;

import java.util.List;

/**
 * Streams a published run's results into a comparison, one database page at a time.
 *
 * <p>The alternative — {@code repository.results(runId, 0, Integer.MAX_VALUE)} — reads the entire result
 * set into a list before the comparison starts, which makes a service-side reconciliation of a large run
 * fail for a reason that has nothing to do with reconciliation. Paging keeps the caller's heap flat and
 * gives the database a query it can answer from an index instead of a full sort.
 *
 * <p>Pages are read by offset rather than by cursor because the results table is keyed
 * {@code (run_id, seq)} and the run is immutable once published, so an offset scan cannot drift.
 */
final class RepositoryAmountRows implements AmountRows {

    /** Rows per query. Large enough to amortise the round trip, small enough to stay out of the heap. */
    static final int PAGE_SIZE = 5_000;

    private final RunRepository repository;
    private final String runId;

    RepositoryAmountRows(RunRepository repository, String runId) {
        this.repository = repository;
        this.runId = runId;
    }

    @Override
    public void stream(RowConsumer consumer) {
        int offset = 0;
        while (true) {
            List<RunSink.ResultLine> page = repository.results(runId, offset, PAGE_SIZE);
            for (RunSink.ResultLine line : page) {
                consumer.accept(line.coord().key(), line.amount());
            }
            if (page.size() < PAGE_SIZE) {
                return;
            }
            offset += page.size();
        }
    }

    @Override
    public String label() {
        return "run " + runId;
    }
}
