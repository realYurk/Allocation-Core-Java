package io.allocation.kernel.service;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * A data source over one SQLite file, for tests only.
 *
 * <p>Deliberately not a pool: every {@link #getConnection()} opens a fresh connection, which is the
 * worst possible performance and the best possible isolation between assertions. A pooling
 * implementation would hide the "did this actually commit" question behind a cached connection.
 *
 * <p>{@code PRAGMA foreign_keys} is issued per connection because SQLite defaults it off, and a schema
 * whose foreign keys are silently unenforced is worse than no foreign keys at all — the tests would
 * pass while the constraint did nothing.
 */
final class SqliteDataSource implements DataSource {

    private final String url;

    SqliteDataSource(Path database) {
        this.url = "jdbc:sqlite:" + database.toAbsolutePath();
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException("SQLite has no credentials");
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
