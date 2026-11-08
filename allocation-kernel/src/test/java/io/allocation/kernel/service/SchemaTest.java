package io.allocation.kernel.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes the shipped DDL, so the schema is a tested artefact rather than a paragraph in a document.
 *
 * <p>SQLite is used because it is embeddable; the MySQL and PostgreSQL variants carry the same columns
 * and are reviewed by eye. What this catches is the class of mistake that only shows up at deploy time:
 * a constraint that no database will accept, a column type that cannot hold a 64-bit amount, and a
 * duplicate idempotency key that is silently allowed.
 */
class SchemaTest {

    private static final Path DDL = Path.of("..", "ddl", "allocation-sqlite.sql");

    @TempDir
    Path directory;

    private String url;

    @BeforeEach
    void loadSchema() throws IOException, SQLException {
        assertTrue(Files.isRegularFile(DDL), "missing " + DDL.toAbsolutePath());
        url = "jdbc:sqlite:" + directory.resolve("allocation.db").toAbsolutePath();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String sql : statements(Files.readString(DDL, StandardCharsets.UTF_8))) {
                statement.execute(sql);
            }
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    /** Strips comment lines and splits on semicolons; the DDL contains no quoted semicolons. */
    private static List<String> statements(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") || trimmed.isEmpty()) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                out.add(current.toString());
                current.setLength(0);
            }
        }
        return out;
    }

    @Test
    void createsTheFiveTables() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }

        assertEquals(List.of("allocation_line", "allocation_pool", "allocation_reconcile",
                "allocation_rule", "allocation_run"), tables);
    }

    @Test
    void holdsAFullSixtyFourBitAmount() throws SQLException {
        insertRun("run-1", null, Long.MAX_VALUE, Long.MIN_VALUE);

        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT input_amount, output_amount FROM allocation_run WHERE run_id = 'run-1'")) {
            assertTrue(rs.next());
            assertEquals(Long.MAX_VALUE, rs.getLong(1));
            assertEquals(Long.MIN_VALUE, rs.getLong(2));
        }
    }

    @Test
    void rejectsASecondRunWithTheSameIdempotencyKey() throws SQLException {
        insertRun("run-1", "2026-01", 0L, 0L);

        // The driver's exception hierarchy differs between vendors, so the message is what is checked:
        // what matters is that the database refuses the duplicate, not how it is spelled.
        SQLException failure = assertThrows(SQLException.class, () -> insertRun("run-2", "2026-01", 0L, 0L));
        assertTrue(failure.getMessage().toLowerCase().contains("unique"), failure.getMessage());
    }

    @Test
    void allowsManyRunsWithoutAnIdempotencyKey() throws SQLException {
        insertRun("run-1", null, 0L, 0L);
        insertRun("run-2", null, 0L, 0L);

        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM allocation_run")) {
            assertEquals(2, rs.getInt(1));
        }
    }

    @Test
    void evidenceCannotReferenceAMissingRun() {
        assertThrows(SQLException.class, () -> {
            try (Connection connection = connect(); Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO allocation_pool"
                        + " (run_id, stage_id, pool_key, input_amount, output_amount, target_count)"
                        + " VALUES ('nope', 'st1', '[1]', 1, 1, 1)");
            }
        });
    }

    private void insertRun(String runId, String idempotencyKey, long input, long output) throws SQLException {
        String sql = "INSERT INTO allocation_run (run_id, rule_code, rule_sha256, idempotency_key,"
                + " request_sha256, status, precision, input_amount, output_amount, pool_count, row_count,"
                + " engine_version, started_at) VALUES ('" + runId + "', 'rule', 'sha', "
                + (idempotencyKey == null ? "NULL" : "'" + idempotencyKey + "'")
                + ", 'req', 'PUBLISHED', 2, " + input + ", " + output + ", 0, 0, '1.0.0', 0)";
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
