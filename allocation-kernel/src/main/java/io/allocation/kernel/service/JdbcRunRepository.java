package io.allocation.kernel.service;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.Engine;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.coord.Coord;
import io.allocation.kernel.exec.RunSink;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistence through plain JDBC, against the schema in {@code ddl/}.
 *
 * <p>Three tables, one job each: {@code allocation_run} is the audit spine, {@code allocation_pool} is
 * the balance proof, {@code allocation_result} is the answer. The ledger and the lineage file stay on
 * disk because they exist to be read as files; the three database tables exist to be queried.
 *
 * <p>Three properties are enforced here rather than trusted to the caller:
 *
 * <ul>
 *   <li><b>Idempotency is a constraint, not a cache.</b> Inserting a run with an
 *       {@code idempotency_key} that already exists fails in the database, so two processes racing on
 *       the same key cannot both win. The loser is told {@code ALLOC_005} and re-reads the winner; no
 *       in-process lock is involved and none would help across a cluster.</li>
 *   <li><b>A terminal run cannot be rewritten.</b> {@code update} carries {@code status IN
 *       ('PENDING','RUNNING')} in its WHERE clause, so an accidental second publish updates zero rows
 *       and fails loudly instead of overwriting history.</li>
 *   <li><b>Evidence is written once.</b> Pools, lines and results use the run's own sequence numbers,
 *       so a second write collides on the primary key. A retry that reached this point is a bug in the
 *       caller, and it is better to hear about it than to silently duplicate an audit trail.</li>
 *   <li><b>A run belongs to one tenant.</b> When a {@link TenantProvider} is configured, every path that
 *       turns a caller's run id into a run adds {@code tenant_id = ?}, and a run belonging to another
 *       tenant does not exist as far as that caller is concerned. The evidence tables are not filtered
 *       and do not need to be: {@code run_id} is a global primary key, so they are only reachable
 *       through a run that was already resolved.</li>
 * </ul>
 *
 * <p>Transaction scope is one artefact: all pools, or all lines, or all results. That is the largest
 * unit that is still atomic, and it is what keeps "PUBLISHED implies evidence present" true. It is also
 * the reason the service buffers evidence before writing it: a million-row result set inside a single
 * transaction is a database problem, and streaming that boundary is M4's job, not this class's.
 *
 * <p>The data source's lifecycle belongs to the host: this class never closes it. In production that is
 * a pool (HikariCP in a Spring Boot application); in a test it is a single connection factory.
 */
public final class JdbcRunRepository implements RunRepository {

    private static final System.Logger LOG = System.getLogger(JdbcRunRepository.class.getName());

    /** Rows per JDBC batch. Large enough to amortise the round trip, small enough to bound driver memory. */
    private static final int BATCH_SIZE = 1_000;

    private static final String INSERT_RUN = """
            INSERT INTO allocation_run (run_id, tenant_id, rule_code, rule_sha256, rule_json,
                                        idempotency_key, request_sha256, precision, status, input_amount,
                                        output_amount, pool_count, row_count, engine_version, failure_code,
                                        failure_message, started_at, finished_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_RUN = """
            SELECT run_id, rule_code, rule_sha256, rule_json, idempotency_key, request_sha256, precision,
                   status, input_amount, output_amount, pool_count, row_count, failure_code,
                   failure_message, started_at, finished_at
              FROM allocation_run
            """;

    private static final String UPDATE_RUN = """
            UPDATE allocation_run
               SET status = ?, input_amount = ?, output_amount = ?, pool_count = ?, row_count = ?,
                   failure_code = ?, failure_message = ?, finished_at = ?
             WHERE run_id = ? AND status IN ('PENDING', 'RUNNING')
            """;

    private static final String INSERT_POOL = """
            INSERT INTO allocation_pool (run_id, stage_id, pool_key, input_amount, output_amount, target_count)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_POOL = """
            SELECT stage_id, pool_key, input_amount, output_amount, target_count
              FROM allocation_pool
             WHERE run_id = ?
             ORDER BY stage_id, pool_key
            """;

    private static final String INSERT_LINE = """
            INSERT INTO allocation_line (run_id, seq, stage_id, rule_id, pool_key, target_key, target_row_id,
                                         pool_amount, share_amount, weight, denominator, allocated_amount)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_LINE_COLUMNS = """
            SELECT stage_id, rule_id, pool_key, target_key, target_row_id, pool_amount, share_amount,
                   weight, denominator, allocated_amount
              FROM allocation_line
             WHERE run_id = ?
            """;

    private static final String SELECT_LINE = SELECT_LINE_COLUMNS + " ORDER BY seq\n";

    private static final String SELECT_LINE_BY_TARGET =
            SELECT_LINE_COLUMNS + "   AND target_key IN (%s)\n ORDER BY seq\n";

    private static final String SELECT_LINE_BY_POOL = SELECT_LINE_COLUMNS
            + "   AND stage_id = ? AND rule_id = ? AND pool_key = ?\n ORDER BY seq\n";

    /** Bound on the IN list, well under every driver's parameter limit and small enough to stay cached. */
    private static final int TARGET_KEYS_PER_QUERY = 400;

    private static final String INSERT_RESULT = """
            INSERT INTO allocation_result (run_id, seq, stage_id, row_id, coordinate, amount)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_RESULT = """
            SELECT stage_id, row_id, coordinate, amount
              FROM allocation_result
             WHERE run_id = ?
             ORDER BY seq
             LIMIT ? OFFSET ?
            """;

    /** Appended to a run lookup when there is a tenant in context. */
    private static final String TENANT_CLAUSE = " AND tenant_id = ?";

    private final DataSource dataSource;
    private final String engineVersion;
    private final TenantProvider tenants;

    public JdbcRunRepository(DataSource dataSource) {
        this(dataSource, Engine.VERSION);
    }

    /**
     * @param engineVersion stamped on every inserted run; a host that shades the engine can say so here
     */
    public JdbcRunRepository(DataSource dataSource, String engineVersion) {
        this(dataSource, engineVersion, TenantProvider.none());
    }

    /** The common host case: a database, and a tenant to scope it to. */
    public JdbcRunRepository(DataSource dataSource, TenantProvider tenants) {
        this(dataSource, Engine.VERSION, tenants);
    }

    /**
     * @param tenants the caller's tenant, consulted when a run is written and when one is looked up;
     *                {@link TenantProvider#none()} keeps the repository single-tenant
     */
    public JdbcRunRepository(DataSource dataSource, String engineVersion, TenantProvider tenants) {
        this.dataSource = dataSource;
        this.engineVersion = engineVersion;
        this.tenants = tenants == null ? TenantProvider.none() : tenants;
    }

    /**
     * The tenant to write, or {@code null} when there is no tenant context.
     *
     * <p>Read once per row moved, on whatever thread the caller is on. The value goes into the row at
     * insert time and is never re-read: a run belongs to the tenant that submitted it, not to whoever
     * happens to query it later.
     */
    private String tenant() {
        String tenant = tenants.currentTenant();
        return tenant == null || tenant.isEmpty() ? null : tenant;
    }

    // ---------------------------------------------------------------- evidence writer

    @Override
    public EvidenceWriter openEvidence(String runId) {
        try {
            Connection connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            return new JdbcEvidenceWriter(runId, connection);
        } catch (SQLException e) {
            throw translate("open an evidence writer", e);
        }
    }

    /**
     * Writes evidence in batches, committing as it goes.
     *
     * <p>Committing per batch rather than once at the end is deliberate: a single transaction covering
     * ten million rows holds that much undo log and locks that many pages, which is a database incident
     * rather than a run. Partial evidence is safe here because nothing reads it until the run is
     * published, and because {@link #close()} removes it when the run did not finish.
     */
    private final class JdbcEvidenceWriter implements EvidenceWriter {

        private final String runId;
        private final Connection connection;
        private final PreparedStatement poolStatement;
        private final PreparedStatement lineStatement;
        private final PreparedStatement resultStatement;
        private long seq;
        private int pending;
        private boolean committed;
        private boolean closed;

        JdbcEvidenceWriter(String runId, Connection connection) throws SQLException {
            this.runId = runId;
            this.connection = connection;
            this.poolStatement = connection.prepareStatement(INSERT_POOL);
            this.lineStatement = connection.prepareStatement(INSERT_LINE);
            this.resultStatement = connection.prepareStatement(INSERT_RESULT);
        }

        @Override
        public void pool(PoolRecord record) {
            try {
                poolStatement.setString(1, runId);
                poolStatement.setString(2, record.stageId());
                poolStatement.setString(3, record.poolKey());
                poolStatement.setLong(4, record.inputAmount());
                poolStatement.setLong(5, record.outputAmount());
                poolStatement.setInt(6, record.targetCount());
                poolStatement.addBatch();
                count();
            } catch (SQLException e) {
                throw translate("write pool evidence", e);
            }
        }

        @Override
        public void line(RunSink.LedgerLine line) {
            try {
                lineStatement.setString(1, runId);
                lineStatement.setLong(2, ++seq);
                lineStatement.setString(3, line.stage());
                lineStatement.setString(4, line.rule());
                lineStatement.setString(5, line.pool());
                lineStatement.setString(6, line.target());
                lineStatement.setString(7, line.targetRowId());
                lineStatement.setLong(8, line.poolAmount());
                lineStatement.setLong(9, line.shareAmount());
                lineStatement.setLong(10, line.driverWeight());
                lineStatement.setLong(11, line.driverDenominator());
                lineStatement.setLong(12, line.allocatedAmount());
                lineStatement.addBatch();
                count();
            } catch (SQLException e) {
                throw translate("write ledger evidence", e);
            }
        }

        @Override
        public void result(RunSink.ResultLine line) {
            try {
                resultStatement.setString(1, runId);
                resultStatement.setLong(2, ++seq);
                resultStatement.setString(3, line.stage());
                resultStatement.setString(4, line.rowId());
                resultStatement.setString(5, line.coord().key());
                resultStatement.setLong(6, line.amount());
                resultStatement.addBatch();
                count();
            } catch (SQLException e) {
                throw translate("write results", e);
            }
        }

        private void count() throws SQLException {
            if (++pending >= BATCH_SIZE) {
                flush();
            }
        }

        private void flush() throws SQLException {
            poolStatement.executeBatch();
            lineStatement.executeBatch();
            resultStatement.executeBatch();
            connection.commit();
            pending = 0;
        }

        @Override
        public void commit() {
            if (committed) {
                throw new AllocationException(ErrorCode.INTERNAL,
                        "evidence for " + runId + " was committed twice");
            }
            try {
                flush();
                committed = true;
            } catch (SQLException e) {
                throw translate("commit evidence", e);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (!committed) {
                    try {
                        connection.rollback();
                    } catch (SQLException ignored) {
                        // The rollback failing is not interesting; the discard below is what matters.
                    }
                    discardEvidence(runId, connection);
                }
            } finally {
                closeQuietly(resultStatement, lineStatement, poolStatement, connection);
            }
        }
    }

    /**
     * Removes whatever a failed run wrote.
     *
     * <p>Runs on the writer's own connection when it still works, and on a fresh one otherwise. A failure
     * to discard is logged rather than thrown: it must not replace the error that caused the run to fail.
     */
    private void discardEvidence(String runId, Connection preferred) {
        String[] statements = {
                "DELETE FROM allocation_result WHERE run_id = ?",
                "DELETE FROM allocation_line WHERE run_id = ?",
                "DELETE FROM allocation_pool WHERE run_id = ?"};
        Connection connection = preferred;
        boolean owned = false;
        try {
            if (connection == null || connection.isClosed()) {
                connection = dataSource.getConnection();
                owned = true;
                connection.setAutoCommit(false);
            }
            for (String sql : statements) {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, runId);
                    statement.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "run " + runId + " failed and its partial evidence could not be discarded", e);
        } finally {
            if (owned && connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // Closing a connection we already failed to use adds nothing.
                }
            }
        }
    }

    private static void closeQuietly(AutoCloseable... resources) {
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Exception ignored) {
                // Closing on the failure path; the caller already has the real error.
            }
        }
    }

    // ---------------------------------------------------------------- runs

    @Override
    public Optional<RunRecord> find(String runId) {
        String tenant = tenant();
        return tenant == null
                ? queryOne(SELECT_RUN + " WHERE run_id = ?", runId)
                : queryOne(SELECT_RUN + " WHERE run_id = ?" + TENANT_CLAUSE, runId, tenant);
    }

    @Override
    public Optional<RunRecord> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            // A run submitted without a key is never findable by one. Answering empty here rather than
            // issuing "= NULL" keeps the caller's intent explicit.
            return Optional.empty();
        }
        // Always scoped, with the empty tenant standing in for "no context". A key is only unique within
        // a tenant, so an unscoped lookup would let a batch job collide with a company's key — and, worse,
        // hand it that company's run. Reads of a run id are a different question, answered by find().
        return queryOne(SELECT_RUN + " WHERE idempotency_key = ?" + TENANT_CLAUSE, idempotencyKey,
                Objects.requireNonNullElse(tenant(), ""));
    }

    /**
     * Runs in a given state, oldest first.
     *
     * <p>Deliberately <em>not</em> tenant-filtered. This is an operator's query, not a caller's: the
     * startup sweep asks "which runs did a dead process leave behind", and the honest answer is every
     * tenant's. A host that wants a per-tenant view of unfinished work has the columns for it.
     *
     * <p>Ordered by {@code started_at} so a caller that is looking for abandoned runs walks them in the
     * order they died, and limited so a database with a year of unfinished rows does not produce a list
     * the caller cannot use. {@code idx_run_status} covers it.
     */
    @Override
    public List<RunRecord> byStatus(Status status, int limit) {
        String sql = SELECT_RUN + " WHERE status = ? ORDER BY started_at ASC LIMIT ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                List<RunRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(run(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw translate("read runs by status", e);
        }
    }

    private Optional<RunRecord> queryOne(String sql, String... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(1 + i, parameters[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(run(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("read a run", e);
        }
    }

    @Override
    public void insert(RunRecord run) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_RUN)) {
            statement.setString(1, run.runId());
            // '' rather than NULL, so the column is never ambiguous and the composite unique key behaves
            // the same whether or not the host has tenants.
            statement.setString(2, Objects.requireNonNullElse(tenant(), ""));
            statement.setString(3, run.ruleCode());
            statement.setString(4, run.ruleSha256());
            statement.setString(5, run.ruleJson());
            statement.setString(6, run.idempotencyKey());
            statement.setString(7, run.requestSha256());
            statement.setInt(8, run.precision());
            statement.setString(9, run.status().name());
            statement.setLong(10, run.inputAmount());
            statement.setLong(11, run.outputAmount());
            statement.setInt(12, run.poolCount());
            statement.setLong(13, run.rowCount());
            statement.setString(14, engineVersion);
            statement.setString(15, run.failureCode());
            statement.setString(16, run.failureMessage());
            statement.setLong(17, run.startedAtEpochMs());
            if (run.finishedAtEpochMs() == null) {
                statement.setNull(18, java.sql.Types.BIGINT);
            } else {
                statement.setLong(18, run.finishedAtEpochMs());
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                throw new AllocationException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "run " + run.runId() + " or its idempotency key already exists", e);
            }
            throw translate("insert a run", e);
        }
    }

    /**
     * Applies a terminal status.
     *
     * <p>Not tenant-filtered, and it does not need to be: the only caller is the worker that was handed
     * the record at submit time, on a thread that has no tenant context of its own. The guard that
     * matters here is the status one.
     */
    @Override
    public void update(RunRecord run) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(UPDATE_RUN)) {
                statement.setString(1, run.status().name());
                statement.setLong(2, run.inputAmount());
                statement.setLong(3, run.outputAmount());
                statement.setInt(4, run.poolCount());
                statement.setLong(5, run.rowCount());
                statement.setString(6, run.failureCode());
                statement.setString(7, run.failureMessage());
                if (run.finishedAtEpochMs() == null) {
                    statement.setNull(8, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(8, run.finishedAtEpochMs());
                }
                statement.setString(9, run.runId());
                int updated = statement.executeUpdate();
                connection.commit();
                if (updated == 0) {
                    // Either the run does not exist, or it already reached a terminal status. Both are
                    // caller errors; silently succeeding would let a second publish overwrite history.
                    throw new AllocationException(ErrorCode.IDEMPOTENCY_CONFLICT,
                            "run " + run.runId() + " is missing or already finished; " + run.status()
                                    + " was not applied");
                }
            } catch (SQLException e) {
                connection.rollback();
                throw translate("update a run", e);
            }
        } catch (SQLException e) {
            throw translate("update a run", e);
        }
    }

    // ---------------------------------------------------------------- evidence

    @Override
    public void savePools(String runId, List<PoolRecord> pools) {
        if (pools.isEmpty()) {
            return;
        }
        inTransaction("save pool evidence", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT_POOL)) {
                int pending = 0;
                for (PoolRecord pool : pools) {
                    statement.setString(1, runId);
                    statement.setString(2, pool.stageId());
                    statement.setString(3, pool.poolKey());
                    statement.setLong(4, pool.inputAmount());
                    statement.setLong(5, pool.outputAmount());
                    statement.setInt(6, pool.targetCount());
                    statement.addBatch();
                    if (++pending == BATCH_SIZE) {
                        statement.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    statement.executeBatch();
                }
            }
        });
    }

    @Override
    public void saveLines(String runId, List<RunSink.LedgerLine> lines) {
        if (lines.isEmpty()) {
            return;
        }
        inTransaction("save ledger evidence", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT_LINE)) {
                int pending = 0;
                long seq = 0L;
                for (RunSink.LedgerLine line : lines) {
                    statement.setString(1, runId);
                    statement.setLong(2, ++seq);
                    statement.setString(3, line.stage());
                    statement.setString(4, line.rule());
                    statement.setString(5, line.pool());
                    statement.setString(6, line.target());
                    statement.setString(7, line.targetRowId());
                    statement.setLong(8, line.poolAmount());
                    statement.setLong(9, line.shareAmount());
                    statement.setLong(10, line.driverWeight());
                    statement.setLong(11, line.driverDenominator());
                    statement.setLong(12, line.allocatedAmount());
                    statement.addBatch();
                    if (++pending == BATCH_SIZE) {
                        statement.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    statement.executeBatch();
                }
            }
        });
    }

    @Override
    public void saveResults(String runId, List<RunSink.ResultLine> results) {
        if (results.isEmpty()) {
            return;
        }
        inTransaction("save results", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT_RESULT)) {
                int pending = 0;
                long seq = 0L;
                for (RunSink.ResultLine line : results) {
                    statement.setString(1, runId);
                    statement.setLong(2, ++seq);
                    statement.setString(3, line.stage());
                    statement.setString(4, line.rowId());
                    // The coordinate is stored as the engine's own canonical key, so a value read back
                    // is byte-identical to a value computed now. One column instead of up to thirty.
                    statement.setString(5, line.coord().key());
                    statement.setLong(6, line.amount());
                    statement.addBatch();
                    if (++pending == BATCH_SIZE) {
                        statement.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    statement.executeBatch();
                }
            }
        });
    }

    @Override
    public List<PoolRecord> pools(String runId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_POOL)) {
            statement.setString(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                List<PoolRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new PoolRecord(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                            rs.getInt(5)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw translate("read pool evidence", e);
        }
    }

    @Override
    public List<RunSink.LedgerLine> lines(String runId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_LINE)) {
            statement.setString(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                List<RunSink.LedgerLine> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new RunSink.LedgerLine(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getLong(8),
                            rs.getLong(9), rs.getLong(10)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw translate("read ledger evidence", e);
        }
    }

    /**
     * Reads only the ledger lines of the given coordinates.
     *
     * <p>Chunked rather than one statement with a thousand placeholders: the IN list of a 500-row page is
     * 500 parameters, which is legal on MySQL and PostgreSQL but starts to defeat statement caching, and
     * the chunk size is a number this class can defend.
     */
    @Override
    public List<RunSink.LedgerLine> linesForTargets(String runId, Collection<String> targetKeys) {
        if (targetKeys == null || targetKeys.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(new LinkedHashSet<>(targetKeys));
        List<RunSink.LedgerLine> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (int start = 0; start < keys.size(); start += TARGET_KEYS_PER_QUERY) {
                List<String> chunk = keys.subList(start, Math.min(keys.size(), start + TARGET_KEYS_PER_QUERY));
                String sql = SELECT_LINE_BY_TARGET.formatted("?,".repeat(chunk.size() - 1) + "?");
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, runId);
                    for (int i = 0; i < chunk.size(); i++) {
                        statement.setString(2 + i, chunk.get(i));
                    }
                    readLines(statement, out);
                }
            }
            return out;
        } catch (SQLException e) {
            throw translate("read ledger evidence for targets", e);
        }
    }

    @Override
    public List<RunSink.LedgerLine> linesForPool(String runId, String stageId, String ruleId, String poolKey) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_LINE_BY_POOL)) {
            statement.setString(1, runId);
            statement.setString(2, stageId);
            statement.setString(3, ruleId);
            statement.setString(4, poolKey);
            List<RunSink.LedgerLine> out = new ArrayList<>();
            readLines(statement, out);
            return out;
        } catch (SQLException e) {
            throw translate("read ledger evidence for a pool", e);
        }
    }

    private static void readLines(PreparedStatement statement, List<RunSink.LedgerLine> into) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                into.add(new RunSink.LedgerLine(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getLong(8),
                        rs.getLong(9), rs.getLong(10)));
            }
        }
    }

    @Override
    public List<RunSink.ResultLine> results(String runId, int offset, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_RESULT)) {
            statement.setString(1, runId);
            statement.setInt(2, limit);
            statement.setInt(3, Math.max(0, offset));
            try (ResultSet rs = statement.executeQuery()) {
                List<RunSink.ResultLine> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new RunSink.ResultLine(rs.getString(1), rs.getString(2),
                            coordinate(rs.getString(3)), rs.getLong(4)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw translate("read results", e);
        }
    }

    /**
     * Parses a stored coordinate key.
     *
     * <p>Corruption here means someone edited the table by hand, which is exactly the kind of fault that
     * must surface as a named error rather than as a bare {@code IllegalArgumentException} in a stack
     * trace nobody reads.
     */
    private static Coord coordinate(String key) {
        try {
            return Coord.parse(key);
        } catch (RuntimeException e) {
            throw new AllocationException(ErrorCode.INTERNAL, "stored coordinate is corrupt: " + key, e);
        }
    }

    @Override
    public int resultCount(String runId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM allocation_result WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw translate("count results", e);
        }
    }

    // ---------------------------------------------------------------- plumbing

    /** One unit of work; the caller's lambda runs inside a transaction that is rolled back on failure. */
    @FunctionalInterface
    private interface Work {
        void run(Connection connection) throws SQLException;
    }

    private void inTransaction(String what, Work work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                work.run(connection);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } catch (RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                throw new AllocationException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        what + " was already recorded for this run", e);
            }
            throw translate(what, e);
        }
    }

    private static RunRecord run(ResultSet rs) throws SQLException {
        long finished = rs.getLong("finished_at");
        Long finishedAt = rs.wasNull() ? null : finished;
        return new RunRecord(rs.getString("run_id"), rs.getString("rule_code"), rs.getString("rule_sha256"),
                rs.getString("rule_json"), rs.getString("idempotency_key"), rs.getString("request_sha256"),
                rs.getInt("precision"), Status.valueOf(rs.getString("status")), rs.getLong("input_amount"),
                rs.getLong("output_amount"), rs.getInt("pool_count"), rs.getLong("row_count"),
                rs.getString("failure_code"), rs.getString("failure_message"), rs.getLong("started_at"),
                finishedAt);
    }

    /**
     * Recognises a uniqueness violation across the three supported databases.
     *
     * <p>There is no portable signal for this: SQLite reports SQLState 23000 with message "UNIQUE
     * constraint failed", PostgreSQL reports 23505, MySQL reports vendor code 1062. Matching on all
     * three is uglier than a single check and considerably better than treating a lost idempotency race
     * as an internal error.
     */
    private static boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        // PostgreSQL is precise: 23505 is unique_violation, and 23503 (foreign key) must not be
        // mistaken for it. MySQL uses 23000 for both duplicate key and foreign key, so the vendor code
        // is the only discriminator. SQLite reports 23000 with a message.
        if ("23505".equals(state) || e.getErrorCode() == 1_062) {
            return true;
        }
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("unique") || lower.contains("duplicate");
    }

    private static AllocationException translate(String what, SQLException e) {
        return new AllocationException(ErrorCode.INTERNAL, "cannot " + what + ": " + e.getMessage(), e);
    }
}
