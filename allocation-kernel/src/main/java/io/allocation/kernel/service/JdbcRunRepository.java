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
import java.util.List;
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

    /** Rows per JDBC batch. Large enough to amortise the round trip, small enough to bound driver memory. */
    private static final int BATCH_SIZE = 1_000;

    private static final String INSERT_RUN = """
            INSERT INTO allocation_run (run_id, rule_code, rule_sha256, idempotency_key, request_sha256,
                                        precision, status, input_amount, output_amount, pool_count, row_count,
                                        engine_version, failure_code, failure_message, started_at, finished_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_RUN = """
            SELECT run_id, rule_code, rule_sha256, idempotency_key, request_sha256, precision, status,
                   input_amount, output_amount, pool_count, row_count, failure_code, failure_message,
                   started_at, finished_at
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

    private static final String SELECT_LINE = """
            SELECT stage_id, rule_id, pool_key, target_key, target_row_id, pool_amount, share_amount,
                   weight, denominator, allocated_amount
              FROM allocation_line
             WHERE run_id = ?
             ORDER BY seq
            """;

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

    private final DataSource dataSource;
    private final String engineVersion;

    public JdbcRunRepository(DataSource dataSource) {
        this(dataSource, Engine.VERSION);
    }

    /**
     * @param engineVersion stamped on every inserted run; a host that shades the engine can say so here
     */
    public JdbcRunRepository(DataSource dataSource, String engineVersion) {
        this.dataSource = dataSource;
        this.engineVersion = engineVersion;
    }

    // ---------------------------------------------------------------- runs

    @Override
    public Optional<RunRecord> find(String runId) {
        return queryOne(SELECT_RUN + " WHERE run_id = ?", runId);
    }

    @Override
    public Optional<RunRecord> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            // A run submitted without a key is never findable by one. Answering empty here rather than
            // issuing "= NULL" keeps the caller's intent explicit.
            return Optional.empty();
        }
        return queryOne(SELECT_RUN + " WHERE idempotency_key = ?", idempotencyKey);
    }

    private Optional<RunRecord> queryOne(String sql, String parameter) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
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
            statement.setString(2, run.ruleCode());
            statement.setString(3, run.ruleSha256());
            statement.setString(4, run.idempotencyKey());
            statement.setString(5, run.requestSha256());
            statement.setInt(6, run.precision());
            statement.setString(7, run.status().name());
            statement.setLong(8, run.inputAmount());
            statement.setLong(9, run.outputAmount());
            statement.setInt(10, run.poolCount());
            statement.setLong(11, run.rowCount());
            statement.setString(12, engineVersion);
            statement.setString(13, run.failureCode());
            statement.setString(14, run.failureMessage());
            statement.setLong(15, run.startedAtEpochMs());
            if (run.finishedAtEpochMs() == null) {
                statement.setNull(16, java.sql.Types.BIGINT);
            } else {
                statement.setLong(16, run.finishedAtEpochMs());
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
                rs.getString("idempotency_key"), rs.getString("request_sha256"), rs.getInt("precision"),
                Status.valueOf(rs.getString("status")), rs.getLong("input_amount"), rs.getLong("output_amount"),
                rs.getInt("pool_count"), rs.getLong("row_count"), rs.getString("failure_code"),
                rs.getString("failure_message"), rs.getLong("started_at"), finishedAt);
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
