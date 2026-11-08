package io.allocation.kernel.service;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.coord.Coord;
import io.allocation.kernel.exec.RunSink;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * — hold when the SQL actually goes to MySQL. The last test pushes one complete run through the full
 * service layer, exercising the streaming evidence path ({@code RepositorySink} → batched commits) that
 * the direct repository calls above do not touch. That is the gap between "the three files agree" and
 * "the deployed one works".
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

    // ---------------------------------------------------------------- end-to-end through the service layer

    /**
     * One complete run through the service, persisted in MySQL.
     *
     * <p>The repository tests above call {@code savePools}/{@code saveResults} directly — the bulk path.
     * A real run writes through {@code RepositorySink} → {@code JdbcEvidenceWriter}, which batches and
     * commits as it goes, and which is the path that most often trips over a dialect's transaction or
     * batch semantics. This test proves that path against MySQL: submit, wait for the terminal status,
     * and read the answer back through the database rather than from the object the service happened to
     * hold.
     */
    @Test
    void aFullRunSurvivesTheStreamingEvidencePathThroughMySQL(@TempDir Path directory) throws IOException {
        String rule = """
                {
                  "schema": "allocation-ir/2.0",
                  "rounding_policy": "LARGEST_REMAINDER_IDENTITY",
                  "name": "mysql e2e",
                  "precision": 2,
                  "dimensions": ["plant", "account"],
                  "immutable_dimensions": [],
                  "hierarchies": {},
                  "datasets": {
                    "amounts": {"amounts": "amounts.csv"},
                    "drivers": {"drivers": "drivers.csv"}
                  },
                  "stages": [
                    {
                      "id": "st1",
                      "inputs": [{"from": "amounts", "share": "1"}],
                      "source": {"grouping": {"plant": "AS_IS", "account": "TOTAL"}, "filters": {}},
                      "target": {"plant": {"mode": "SOURCE"}, "account": {"mode": "INCLUDE", "include": ["A1", "A2"]}},
                      "conservation_keys": ["plant"],
                      "allocations": [
                        {"id": "r1", "share": "1", "driver_dataset": "drivers", "weight_field": "weight"}
                      ]
                    }
                  ]
                }
                """;
        Files.writeString(directory.resolve("rule.json"), rule, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("amounts.csv"),
                "plant,account,amount\nP1,SRC,100.00\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("drivers.csv"),
                "plant,account,weight\nP1,A1,1\nP1,A2,1\n", StandardCharsets.UTF_8);

        DefaultAllocationTemplate template = new DefaultAllocationTemplate(repository,
                new DirectoryRuleResolver(directory), directory.resolve("work"));
        try {
            AllocationTemplate.SubmitResult submitted = template.submit(RunCommand.builder()
                    .ruleCode("rule")
                    .source(new AmountSource.Csv(directory))
                    .build());
            awaitTerminal(submitted.runId());

            RunResult result = template.result(submitted.runId(), 1, 10);
            assertEquals(2, result.total());
            assertEquals(2, result.lines().size());
            assertEquals(Map.of("plant", "P1", "account", "A1"), result.lines().get(0).coord());
            assertEquals("50.00", result.lines().get(0).amount().format());
            assertTrue(result.conservation().balanced());
        } finally {
            template.close();
        }
    }

    // ---------------------------------------------------------------- helpers

    private void awaitTerminal(String runId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            RunRepository.Status status = repository.find(runId).orElseThrow().status();
            if (status.terminal()) {
                return;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("run " + runId + " did not finish within ten seconds");
    }

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
