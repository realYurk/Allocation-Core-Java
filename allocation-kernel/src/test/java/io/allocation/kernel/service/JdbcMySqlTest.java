package io.allocation.kernel.service;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.coord.Coord;
import io.allocation.kernel.exec.RunSink;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped MySQL schema, executed by a real MySQL server.
 *
 * <p>This test is <em>opt-in</em>: it runs only when {@code -Dmysql.url} is present, because CI has no
 * MySQL and must not fail because of one. It is the first and only place {@code ddl/allocation-mysql8.sql}
 * has been exercised by the engine it targets — every previous check compared column names against the
 * other two dialects, which cannot catch a reserved word or an over-long key.
 *
 * <p>Run it against a local server with:
 * <pre>
 *   mvn -pl allocation-kernel test -Dtest=JdbcMySqlTest \
 *       -Dmysql.url=jdbc:mysql://localhost:3306 -Dmysql.user=root -Dmysql.password=123456
 * </pre>
 *
 * <p>What it proves is deliberately narrower than the SQLite suite (which runs every assertion on every
 * CI build): the MySQL DDL parses and creates four usable tables, and the repository's core invariants —
 * round-trip, idempotency, keyset pagination, tenant scoping, unicode coordinates, status sweep ordering
 * — hold when the SQL actually goes to MySQL. That is the gap between "the three files agree" and "the
 * deployed one works".
 */
@EnabledIfSystemProperty(named = "mysql.url", matches = ".+")
class JdbcMySqlTest {

    private static DataSource dataSource;
    private static JdbcRunRepository repository;

    @BeforeAll
    static void setUp() throws IOException, SQLException {
        dataSource = MySqlFixture.freshDatabase();
        repository = new JdbcRunRepository(dataSource, "1.0.0-test");
    }

    @AfterAll
    static void tearDown() throws SQLException {
        // Drop the throwaway schema so a developer's server is not left littered with test databases.
        String schema = System.getProperty("mysql.schema");
        if (schema != null && !schema.isBlank()) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + schema);
            }
        }
    }

    // ---------------------------------------------------------------- schema

    @Test
    void schemaHasTheFourTablesTheEngineWrites() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            try (var rs = statement.executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"
                            + " ORDER BY table_name")) {
                List<String> tables = new ArrayList<>();
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
                assertEquals(List.of("allocation_line", "allocation_pool", "allocation_result", "allocation_run"),
                        tables);
            }
        }
    }

    @Test
    void runTableKeepsEveryColumnThroughMySQL() {
        RunRepository.RunRecord record = new RunRepository.RunRecord(
                "mysql01", "rule-α", "sha-rule", "{\"precision\":2}", "idem-1", "sha-request", 2,
                RunRepository.Status.PUBLISHED, 12_345L, 12_345L, 3, 42L, null, null,
                1_700_000_000_000L, 1_700_000_001_000L);
        repository.insert(record);

        RunRepository.RunRecord read = repository.find("mysql01").orElseThrow();
        assertEquals(record, read);
    }

    @Test
    void refusesASecondRunWithTheSameIdempotencyKey() {
        repository.insert(run("mysql02a", "same-key", 0L, 0L));
        AllocationException e = assertThrows(AllocationException.class,
                () -> repository.insert(run("mysql02b", "same-key", 0L, 0L)));
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, e.code());
    }

    @Test
    void keysetPaginationCoversEveryRowExactlyOnce() {
        repository.insert(run("mysql03", null, 0L, 0L));
        List<RunSink.ResultLine> all = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            all.add(new RunSink.ResultLine("st1", "row-" + i, new Coord(new String[]{"P1", "A" + i}), i));
        }
        repository.saveResults("mysql03", all);

        assertEquals(25, repository.resultCount("mysql03"));
        List<RunSink.ResultLine> collected = new ArrayList<>();
        for (long offset = 0; offset < 25; offset += 7) {
            collected.addAll(repository.results("mysql03", offset, 7));
        }
        assertEquals(all, collected);
    }

    @Test
    void unicodeCoordinatesSurviveTheRoundTrip() {
        repository.insert(run("mysql04", null, 0L, 0L));
        // utf8mb4 is the whole reason the columns are LONGTEXT, not VARCHAR(1024): a coordinate's member
        // can be any business string, including CJK and emoji.
        List<RunSink.ResultLine> results = List.of(
                new RunSink.ResultLine("st1", "row-1", new Coord(new String[]{"成本中心", "北京"}), 100L),
                new RunSink.ResultLine("st1", "row-2", new Coord(new String[]{"成本中心", "上海📈"}), 200L));
        repository.saveResults("mysql04", results);

        assertEquals(results, repository.results("mysql04", 0, 10));
    }

    @Test
    void aRunBelongsToOneTenantAndAnotherTenantsRunDoesNotExist() {
        repository.insert(run("mysql05", null, 0L, 0L));

        JdbcRunRepository otherTenant = new JdbcRunRepository(dataSource, "1.0.0-test",
                () -> "other-tenant");
        assertTrue(otherTenant.find("mysql05").isEmpty());
    }

    @Test
    void statusSweepOrdersOldestFirstAndHonoursTheLimit() {
        repository.insert(state("mysql06old", RunRepository.Status.RUNNING, 1_000L));
        repository.insert(state("mysql06newer", RunRepository.Status.RUNNING, 2_000L));
        repository.insert(state("mysql06finished", RunRepository.Status.PUBLISHED, 500L));

        assertEquals(List.of("mysql06old", "mysql06newer"),
                repository.byStatus(RunRepository.Status.RUNNING, 10).stream()
                        .map(RunRepository.RunRecord::runId).toList());
        assertEquals(List.of("mysql06old"),
                repository.byStatus(RunRepository.Status.RUNNING, 1).stream()
                        .map(RunRepository.RunRecord::runId).toList());
    }

    @Test
    void poolEvidenceRoundTripsInCanonicalOrder() {
        repository.insert(run("mysql07", null, 0L, 0L));
        List<RunRepository.PoolRecord> pools = List.of(
                new RunRepository.PoolRecord("st2", "[\"P1\"]", 10L, 10L, 2),
                new RunRepository.PoolRecord("st1", "[\"P2\"]", 30L, 30L, 1),
                new RunRepository.PoolRecord("st1", "[\"P1\"]", 20L, 20L, 3));
        repository.savePools("mysql07", pools);

        assertEquals(List.of(
                        new RunRepository.PoolRecord("st1", "[\"P1\"]", 20L, 20L, 3),
                        new RunRepository.PoolRecord("st1", "[\"P2\"]", 30L, 30L, 1),
                        new RunRepository.PoolRecord("st2", "[\"P1\"]", 10L, 10L, 2)),
                repository.pools("mysql07"));
    }

    // ---------------------------------------------------------------- helpers

    private static RunRepository.RunRecord run(String runId, String key, long input, long output) {
        return new RunRepository.RunRecord(runId, "rule-α", "sha-rule", null, key, "sha-request", 2,
                RunRepository.Status.PUBLISHED, input, output, 3, 42L, null, null,
                1_700_000_000_000L, 1_700_000_001_000L);
    }

    private static RunRepository.RunRecord state(String runId, RunRepository.Status status, long startedAt) {
        return new RunRepository.RunRecord(runId, "rule-α", "sha-rule", null, null, "sha-request", 2,
                status, 0L, 0L, 0, 0L, null, null, startedAt, null);
    }
}
