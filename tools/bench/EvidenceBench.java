import io.allocation.kernel.coord.Coord;
import io.allocation.kernel.exec.RunSink;
import io.allocation.kernel.service.JdbcRunRepository;
import io.allocation.kernel.service.RunRepository;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

/**
 * Writes N evidence rows through {@link RunRepository.EvidenceWriter} and reports time and heap.
 *
 * <p>This is deliberately outside the Maven build: it is a measuring instrument, not a test, and
 * running it as part of the gate would slow the gate down for a number nobody reads on every commit.
 * It lives in {@code tools/} rather than a scratch directory so that the figures in
 * {@code docs/PERF.md} can be reproduced by anyone who checks out the repository.
 *
 * <p>Run it with the heap you want to prove sufficient, not with the default:
 *
 * <pre>{@code
 * CP="allocation-kernel/target/classes:$(find ~/.m2/repository/org/xerial/sqlite-jdbc \
 *      ~/.m2/repository/org/slf4j/slf4j-api -name '*.jar' ! -name '*sources*' | paste -sd: -)"
 * javac -cp "$CP" -d .bench/bench-classes tools/bench/EvidenceBench.java
 * java -Xmx64m -XX:+UseSerialGC -cp "$CP:.bench/bench-classes" EvidenceBench 1000000
 * }</pre>
 *
 * <p>The row count is confirmed with {@code SELECT COUNT(*)} after the fact, so a run that silently
 * dropped evidence cannot be mistaken for a fast one.
 */
public final class EvidenceBench {

    public static void main(String[] args) throws Exception {
        int rows = Integer.parseInt(args[0]);
        Class.forName("org.sqlite.JDBC");
        Path db = Path.of(".bench/evidence/bench.db");
        Files.deleteIfExists(db);
        DataSource source = new SqliteSource(db);
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            StringBuilder current = new StringBuilder();
            for (String line : Files.readString(Path.of("ddl/allocation-sqlite.sql")).split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                    continue;
                }
                current.append(line).append('\n');
                if (trimmed.endsWith(";")) {
                    s.execute(current.toString());
                    current.setLength(0);
                }
            }
        }
        JdbcRunRepository repository = new JdbcRunRepository(source);
        repository.insert(new RunRepository.RunRecord("bench", "rule", "sha", null, "req", 2,
                RunRepository.Status.RUNNING, 0, 0, 0, 0, null, null, System.currentTimeMillis(), null));

        long started = System.nanoTime();
        RunRepository.EvidenceWriter writer = repository.openEvidence("bench");
        try {
            for (int i = 0; i < rows; i++) {
                writer.result(new RunSink.ResultLine("st1", "row-" + i,
                        new Coord(new String[]{"P" + (i / 1000), "A" + (i % 1000)}), i));
            }
            writer.commit();
        } finally {
            writer.close();
        }
        long ms = (System.nanoTime() - started) / 1_000_000L;
        Runtime runtime = Runtime.getRuntime();
        System.out.println("rows=" + rows + " elapsed_ms=" + ms
                + " stored=" + repository.resultCount("bench")
                + " heap_max_mb=" + (runtime.maxMemory() >> 20));
    }

    private static final class SqliteSource implements DataSource {
        private final String url;

        SqliteSource(Path db) {
            this.url = "jdbc:sqlite:" + db.toAbsolutePath();
        }

        public Connection getConnection() throws SQLException {
            Connection connection = DriverManager.getConnection(url);
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA foreign_keys = ON");
                s.execute("PRAGMA journal_mode = WAL");
                s.execute("PRAGMA synchronous = OFF");
            }
            return connection;
        }

        public Connection getConnection(String u, String p) { throw new UnsupportedOperationException(); }
        public PrintWriter getLogWriter() { return null; }
        public void setLogWriter(PrintWriter out) { }
        public void setLoginTimeout(int seconds) { }
        public int getLoginTimeout() { return 0; }
        public Logger getParentLogger() { return null; }
        public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
        public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
