package io.allocation.kernel.service;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.exec.RunSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory persistence, for previews, tests, and a single-node deployment that does not want a
 * database.
 *
 * <p>Thread-safe but not durable: restarting loses every run. That is an acceptable default for a
 * library and an unacceptable one for an audit trail, which is why the choice is the host's and not a
 * hidden fallback.
 */
public final class InMemoryRunRepository implements RunRepository {

    private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();
    private final Map<String, String> byIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, List<PoolRecord>> pools = new ConcurrentHashMap<>();
    private final Map<String, List<RunSink.LedgerLine>> lines = new ConcurrentHashMap<>();
    private final Map<String, List<RunSink.ResultLine>> results = new ConcurrentHashMap<>();

    @Override
    public EvidenceWriter openEvidence(String runId) {
        return new EvidenceWriter() {
            private final List<PoolRecord> poolRows = new ArrayList<>();
            private final List<RunSink.LedgerLine> lineRows = new ArrayList<>();
            private final List<RunSink.ResultLine> resultRows = new ArrayList<>();
            private boolean committed;

            @Override
            public void pool(PoolRecord record) {
                poolRows.add(record);
            }

            @Override
            public void line(RunSink.LedgerLine line) {
                lineRows.add(line);
            }

            @Override
            public void result(RunSink.ResultLine line) {
                resultRows.add(line);
            }

            @Override
            public void commit() {
                if (committed) {
                    // Publishing twice would let a second run overwrite a published one's evidence.
                    throw new AllocationException(ErrorCode.INTERNAL,
                            "evidence for " + runId + " was committed twice");
                }
                committed = true;
                pools.put(runId, List.copyOf(poolRows));
                lines.put(runId, List.copyOf(lineRows));
                results.put(runId, List.copyOf(resultRows));
            }

            @Override
            public void close() {
                // Nothing was stored before commit, so there is nothing to discard: an uncommitted
                // writer's rows become garbage along with it.
            }
        };
    }

    @Override
    public Optional<RunRecord> find(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public Optional<RunRecord> findByIdempotencyKey(String idempotencyKey) {
        String runId = byIdempotencyKey.get(idempotencyKey);
        return runId == null ? Optional.empty() : find(runId);
    }

    @Override
    public synchronized void insert(RunRecord run) {
        if (runs.containsKey(run.runId())) {
            throw new AllocationException(ErrorCode.IDEMPOTENCY_CONFLICT, "run " + run.runId() + " already exists");
        }
        if (run.idempotencyKey() != null) {
            String existing = byIdempotencyKey.putIfAbsent(run.idempotencyKey(), run.runId());
            if (existing != null) {
                throw new AllocationException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "idempotency key " + run.idempotencyKey() + " already used by run " + existing);
            }
        }
        runs.put(run.runId(), run);
    }

    @Override
    public void update(RunRecord run) {
        runs.put(run.runId(), run);
    }

    @Override
    public void savePools(String runId, List<PoolRecord> records) {
        pools.put(runId, List.copyOf(records));
    }

    @Override
    public void saveLines(String runId, List<RunSink.LedgerLine> records) {
        lines.put(runId, List.copyOf(records));
    }

    @Override
    public void saveResults(String runId, List<RunSink.ResultLine> records) {
        results.put(runId, List.copyOf(records));
    }

    @Override
    public List<PoolRecord> pools(String runId) {
        return pools.getOrDefault(runId, List.of());
    }

    @Override
    public List<RunSink.LedgerLine> lines(String runId) {
        return lines.getOrDefault(runId, List.of());
    }

    @Override
    public List<RunSink.ResultLine> results(String runId, int offset, int limit) {
        List<RunSink.ResultLine> all = results.getOrDefault(runId, List.of());
        if (offset >= all.size()) {
            return List.of();
        }
        return new ArrayList<>(all.subList(offset, Math.min(all.size(), offset + limit)));
    }

    @Override
    public int resultCount(String runId) {
        return results.getOrDefault(runId, List.of()).size();
    }
}
