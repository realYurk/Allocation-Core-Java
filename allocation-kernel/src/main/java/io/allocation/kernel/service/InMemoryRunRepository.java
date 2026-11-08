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
 *
 * <p>Tenancy is implemented here too, rather than left to the JDBC implementation as "not our problem":
 * a host develops against this one and deploys against that one, and a filter that exists in only one of
 * them is a leak that passes every test written before deployment. {@link TenantIsolationContract} runs
 * the same assertions against both.
 */
public final class InMemoryRunRepository implements RunRepository {

    private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();
    /** Run to tenant, empty string when there is no tenant context. */
    private final Map<String, String> tenantOf = new ConcurrentHashMap<>();
    /** {@code tenant + '\u0000' + key}, because a key is only unique within a tenant. */
    private final Map<String, String> byIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, List<PoolRecord>> pools = new ConcurrentHashMap<>();
    private final Map<String, List<RunSink.LedgerLine>> lines = new ConcurrentHashMap<>();
    private final Map<String, List<RunSink.ResultLine>> results = new ConcurrentHashMap<>();
    private final TenantProvider tenants;

    public InMemoryRunRepository() {
        this(TenantProvider.none());
    }

    public InMemoryRunRepository(TenantProvider tenants) {
        this.tenants = tenants == null ? TenantProvider.none() : tenants;
    }

    private String tenant() {
        String tenant = tenants.currentTenant();
        return tenant == null || tenant.isEmpty() ? null : tenant;
    }

    private static String key(String tenant, String idempotencyKey) {
        return (tenant == null ? "" : tenant) + '\u0000' + idempotencyKey;
    }

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
        RunRecord record = runs.get(runId);
        if (record == null) {
            return Optional.empty();
        }
        String tenant = tenant();
        if (tenant == null) {
            return Optional.of(record);
        }
        // A run belonging to another tenant is indistinguishable from one that does not exist, which is
        // the answer the caller gets from the database-backed repository as well.
        return tenant.equals(tenantOf.getOrDefault(runId, "")) ? Optional.of(record) : Optional.empty();
    }

    @Override
    public Optional<RunRecord> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        // Scoped the same way the database repository scopes it: a key is unique within a tenant, and
        // "no context" is the empty tenant. See JdbcRunRepository#findByIdempotencyKey.
        String runId = byIdempotencyKey.get(key(tenant(), idempotencyKey));
        return runId == null ? Optional.empty() : find(runId);
    }

    @Override
    public List<RunRecord> byStatus(Status status, int limit) {
        return runs.values().stream()
                .filter(run -> run.status() == status)
                .sorted(java.util.Comparator.comparingLong(RunRecord::startedAtEpochMs))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized void insert(RunRecord run) {
        if (runs.containsKey(run.runId())) {
            throw new AllocationException(ErrorCode.IDEMPOTENCY_CONFLICT, "run " + run.runId() + " already exists");
        }
        String tenant = tenant();
        if (run.idempotencyKey() != null) {
            String existing = byIdempotencyKey.putIfAbsent(key(tenant, run.idempotencyKey()), run.runId());
            if (existing != null) {
                throw new AllocationException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "idempotency key " + run.idempotencyKey() + " already used by run " + existing);
            }
        }
        tenantOf.put(run.runId(), tenant == null ? "" : tenant);
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
    public List<RunSink.ResultLine> results(String runId, long afterSeq, int limit) {
        List<RunSink.ResultLine> all = results.getOrDefault(runId, List.of());
        long start = Math.max(0L, afterSeq);
        if (start >= all.size()) {
            return List.of();
        }
        return new ArrayList<>(all.subList((int) start, (int) Math.min(all.size(), start + limit)));
    }

    @Override
    public int resultCount(String runId) {
        return results.getOrDefault(runId, List.of()).size();
    }
}
