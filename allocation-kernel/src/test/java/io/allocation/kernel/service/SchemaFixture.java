package io.allocation.kernel.service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applies the shipped schema to a fresh database.
 *
 * <p>The SQL under test is the file in {@code ddl/}, not a copy of it held in the test. That is the
 * whole point: a schema which is only exercised by a paraphrase proves nothing about the one that gets
 * deployed.
 */
final class SchemaFixture {

    private static final Path DDL = Path.of("..", "ddl", "allocation-sqlite.sql");

    private SchemaFixture() {
    }

    /** A data source over a new, empty database with the shipped schema applied. */
    static DataSource freshDatabase(Path directory) throws IOException, SQLException {
        SqliteDataSource dataSource = new SqliteDataSource(directory.resolve("allocation.db"));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements(read())) {
                statement.execute(sql);
            }
        }
        return dataSource;
    }

    static String read() throws IOException {
        assertTrue(Files.isRegularFile(DDL), "missing " + DDL.toAbsolutePath());
        return Files.readString(DDL, StandardCharsets.UTF_8);
    }

    /**
     * Strips comment lines and splits on semicolons.
     *
     * <p>Blunt on purpose: the shipped DDL contains no semicolons inside string literals, and a real
     * SQL splitter would be more machinery than the file justifies. If that ever stops being true this
     * method will fail loudly rather than quietly executing half a statement.
     */
    static List<String> statements(String text) {
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
}
