package io.allocation.kernel.service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applies the shipped MySQL schema to a real MySQL server, for the opt-in test only.
 *
 * <p>The whole point of {@code JdbcMySqlTest} is that {@code ddl/allocation-mysql8.sql} has never been
 * executed by a server — CI has only SQLite — and a syntax error there is a deploy-time surprise. This
 * fixture is the one place that runs the real file against the real engine, so it reads the file rather
 * than paraphrasing it, exactly like {@link SchemaFixture} does for SQLite.
 */
final class MySqlFixture {

    private static final Path DDL = Path.of("..", "ddl", "allocation-mysql8.sql");

    private MySqlFixture() {
    }

    /**
     * A data source over a freshly created schema on the target server.
     *
     * <p>Connection properties come from system properties, because CI has no MySQL and must not fail:
     * <ul>
     *   <li>{@code mysql.url} — e.g. {@code jdbc:mysql://localhost:3306}</li>
     *   <li>{@code mysql.user} — default {@code root}</li>
     *   <li>{@code mysql.password} — default empty</li>
     * </ul>
     * A unique schema name is generated per call so parallel runs never share state.
     */
    static DataSource freshDatabase() throws SQLException, IOException {
        String url = System.getProperty("mysql.url");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("mysql.url system property is required");
        }
        String user = System.getProperty("mysql.user", "root");
        String password = System.getProperty("mysql.password", "");
        String schema = "alloc_test_" + Long.toUnsignedString(System.nanoTime(), 36);

        try (Connection admin = DriverManager.getConnection(url, user, password);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        String schemaUrl = url + "/" + schema;
        try (Connection connection = DriverManager.getConnection(schemaUrl, user, password);
             Statement statement = connection.createStatement()) {
            for (String sql : SchemaFixture.statements(read())) {
                statement.execute(sql);
            }
        }
        // The generated name is where the test's tear-down drops the schema, so hand it back.
        System.setProperty("mysql.schema", schema);
        return new MySqlDataSource(schemaUrl, user, password, schema);
    }

    private static String read() throws IOException {
        assertTrue(Files.isRegularFile(DDL), "missing " + DDL.toAbsolutePath());
        return Files.readString(DDL, StandardCharsets.UTF_8);
    }

    /**
     * A non-pooling data source over one MySQL schema, mirroring {@code SqliteDataSource}'s isolation:
     * every {@code getConnection()} is a fresh connection, so no cached state can hide a missing commit.
     */
    private static final class MySqlDataSource implements DataSource {

        private final String url;
        private final String user;
        private final String password;
        private final String schema;

        MySqlDataSource(String url, String user, String password, String schema) {
            this.url = url;
            this.user = user;
            this.password = password;
            this.schema = schema;
        }

        String schema() {
            return schema;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException("no java.util.logging bridge");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return false;
        }
    }
}
