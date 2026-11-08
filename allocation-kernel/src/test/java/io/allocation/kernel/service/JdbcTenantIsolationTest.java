package io.allocation.kernel.service;

import io.allocation.kernel.Engine;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/** The tenancy contract, against the shipped schema on a fresh SQLite database. */
class JdbcTenantIsolationTest extends TenantIsolationContract {

    @TempDir
    Path directory;

    private RunRepository repository;

    @Override
    RunRepository repository() {
        if (repository == null) {
            try {
                repository = new JdbcRunRepository(SchemaFixture.freshDatabase(directory),
                        Engine.VERSION, provider());
            } catch (IOException | SQLException e) {
                throw new IllegalStateException("cannot build a test database", e);
            }
        }
        return repository;
    }
}
