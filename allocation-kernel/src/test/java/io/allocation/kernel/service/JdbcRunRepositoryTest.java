package io.allocation.kernel.service;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.coord.Coord;
import io.allocation.kernel.exec.RunSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the JDBC adapter, run against the shipped schema.
 *
 * <p>What is asserted here is not "the SQL executes" but the three properties the service relies on:
 * a duplicate idempotency key is refused by the database, a finished run cannot be rewritten, and
 * evidence is written exactly once. Every one of those is a correctness requirement that a passing
 * happy-path test would miss.
 *
 * <p>SQLite stands in for MySQL and PostgreSQL because it is the only server available offline. The SQL
 * used is the portable subset (no vendor upserts, no RETURNING), and the vendor-specific parts — how a
 * uniqueness violation is reported — are isolated in one method on the adapter.
 */
class JdbcRunRepositoryTest {

    @TempDir
    Path directory;

    private DataSource dataSource;
    private JdbcRunRepository repository;

    @BeforeEach
    void setUp() throws IOException, SQLException {
        dataSource = SchemaFixture.freshDatabase(directory);
        repository = new JdbcRunRepository(dataSource, "1.0.0-test");
    }

    // ---------------------------------------------------------------- runs

    @Test
    void roundTripsEveryColumnIncludingTheSignedExtremes() {
        RunRepository.RunRecord written = run("01RUN", null, Long.MIN_VALUE, Long.MAX_VALUE);

        repository.insert(written);

        RunRepository.RunRecord read = repository.find("01RUN").orElseThrow();
        assertEquals(written, read);
        assertEquals(Long.MIN_VALUE, read.inputAmount());
        assertEquals(Long.MAX_VALUE, read.outputAmount());
        assertEquals(1_700_000_001_000L, read.finishedAtEpochMs());
    }

    @Test
    void keepsAMissingFinishTimeMissing() {
        RunRepository.RunRecord pending = new RunRepository.RunRecord("01PENDING", "rule", "sha", null, "req", 2,
                RunRepository.Status.PENDING, 0L, 0L, 0, 0L, null, null, 1_700_000_000_000L, null);

        repository.insert(pending);

        assertNull(repository.find("01PENDING").orElseThrow().finishedAtEpochMs());
    }

    @Test
    void recordsAFailureAndItsCode() {
        RunRepository.RunRecord pending = new RunRepository.RunRecord("01FAILED", "rule", "sha", null, "req", 2,
                RunRepository.Status.PENDING, 5L, 0L, 0, 0L, null, null, 1_700_000_000_000L, null);
        repository.insert(pending);

        repository.update(pending.failed("ALLOC_004", "no eligible drivers", 1_700_000_002_000L));

        RunRepository.RunRecord read = repository.find("01FAILED").orElseThrow();
        assertEquals(RunRepository.Status.FAILED, read.status());
        assertEquals("ALLOC_004", read.failureCode());
        assertEquals("no eligible drivers", read.failureMessage());
        assertEquals(1_700_000_002_000L, read.finishedAtEpochMs());
    }

    @Test
    void refusesASecondRunWithTheSameIdempotencyKey() {
        repository.insert(run("01A", "2026-01", 1L, 1L));

        AllocationException e = assertThrows(AllocationException.class,
                () -> repository.insert(run("01B", "2026-01", 1L, 1L)));

        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, e.code());
        assertEquals("01A", repository.findByIdempotencyKey("2026-01").orElseThrow().runId());
    }

    @Test
    void treatsAbsentKeyAsAbsentRatherThanAsAValue() {
        repository.insert(run("01A", null, 1L, 1L));
        repository.insert(run("01B", null, 1L, 1L));

        assertTrue(repository.findByIdempotencyKey(null).isEmpty());
        assertTrue(repository.find("01A").isPresent());
        assertTrue(repository.find("01B").isPresent());
    }

    @Test
    void refusesToRewriteARunThatAlreadyFinished() {
        RunRepository.RunRecord pending = new RunRepository.RunRecord("01T", "rule", "sha", null, "req", 2,
                RunRepository.Status.PENDING, 0L, 0L, 0, 0L, null, null, 1_700_000_000_000L, null);
        repository.insert(pending);
        repository.update(pending.published(100L, 100L, 1, 2L, 1_700_000_001_000L));

        AllocationException e = assertThrows(AllocationException.class,
                () -> repository.update(pending.failed("ALLOC_009", "second opinion", 1_700_000_002_000L)));

        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, e.code());
        RunRepository.RunRecord read = repository.find("01T").orElseThrow();
        assertEquals(RunRepository.Status.PUBLISHED, read.status());
        assertNull(read.failureCode(), "a finished run must keep the numbers it was published with");
    }

    @Test
    void rejectsAnUpdateForARunThatDoesNotExist() {
        AllocationException e = assertThrows(AllocationException.class,
                () -> repository.update(run("01MISSING", null, 0L, 0L)));

        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, e.code());
    }

    // ---------------------------------------------------------------- evidence

    @Test
    void writesAndReadsPoolEvidenceInCanonicalOrder() {
        repository.insert(run("01P", null, 0L, 0L));

        List<RunRepository.PoolRecord> pools = List.of(
                new RunRepository.PoolRecord("st2", "[\"P1\"]", 10L, 10L, 2),
                new RunRepository.PoolRecord("st1", "[\"P2\"]", 30L, 30L, 1),
                new RunRepository.PoolRecord("st1", "[\"P1\"]", 20L, 20L, 3));
        repository.savePools("01P", pools);

        assertEquals(List.of(
                        new RunRepository.PoolRecord("st1", "[\"P1\"]", 20L, 20L, 3),
                        new RunRepository.PoolRecord("st1", "[\"P2\"]", 30L, 30L, 1),
                        new RunRepository.PoolRecord("st2", "[\"P1\"]", 10L, 10L, 2)),
                repository.pools("01P"));
    }

    @Test
    void ledgerComesBackInEmissionOrder() {
        repository.insert(run("01L", null, 0L, 0L));
        List<RunSink.LedgerLine> lines = List.of(
                new RunSink.LedgerLine("st1", "r1", "[\"P1\"]", "[\"P1\",\"A2\"]", "row-2", 100L, 100L, 1L, 2L, 50L),
                new RunSink.LedgerLine("st1", "r1", "[\"P1\"]", "[\"P1\",\"A1\"]", "row-1", 100L, 100L, 1L, 2L, 50L));

        repository.saveLines("01L", lines);

        assertEquals(lines, repository.lines("01L"));
    }

    @Test
    void coordinatesSurviveTheRoundTripThroughOneColumn() {
        repository.insert(run("01C", null, 0L, 0L));
        // The awkward cases: a comma, a double quote, a newline, a non-BMP character, and an empty member.
        Coord coord = new Coord(new String[]{"P,1", "say \"hi\"", "line\nbreak", "𝄞", ""});
        repository.saveResults("01C", List.of(new RunSink.ResultLine("st1", "row-1", coord, 12_345L)));

        RunSink.ResultLine read = repository.results("01C", 0, 10).get(0);

        assertEquals(coord.key(), read.coord().key());
        assertEquals("line\nbreak", read.coord().member(2));
        assertEquals("𝄞", read.coord().member(3));
        assertEquals(12_345L, read.amount());
    }

    @Test
    void pagingCoversEveryRowExactlyOnce() {
        repository.insert(run("01R", null, 0L, 0L));
        List<RunSink.ResultLine> all = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            all.add(new RunSink.ResultLine("st1", "row-" + i, new Coord(new String[]{"P1", "A" + i}), i));
        }
        repository.saveResults("01R", all);

        assertEquals(25, repository.resultCount("01R"));
        List<RunSink.ResultLine> collected = new ArrayList<>();
        for (int offset = 0; offset < 25; offset += 7) {
            collected.addAll(repository.results("01R", offset, 7));
        }

        assertEquals(all, collected);
        assertTrue(repository.results("01R", 25, 7).isEmpty());
        assertTrue(repository.results("01R", 0, 0).isEmpty());
    }

    @Test
    void refusesToAppendEvidenceToARunThatAlreadyHasIt() {
        repository.insert(run("01D", null, 0L, 0L));
        List<RunSink.ResultLine> once = List.of(
                new RunSink.ResultLine("st1", "row-1", new Coord(new String[]{"P1", "A1"}), 1L));
        repository.saveResults("01D", once);

        AllocationException e = assertThrows(AllocationException.class,
                () -> repository.saveResults("01D", once));

        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, e.code());
        assertEquals(1, repository.resultCount("01D"));
    }

    @Test
    void reportsACorruptStoredCoordinateAsAnEngineFault() throws SQLException {
        repository.insert(run("01X", null, 0L, 0L));
        repository.saveResults("01X", List.of(
                new RunSink.ResultLine("st1", "row-1", new Coord(new String[]{"P1", "A1"}), 1L)));
        execute("UPDATE allocation_result SET coordinate = '[1,2]' WHERE run_id = '01X'");

        AllocationException e = assertThrows(AllocationException.class, () -> repository.results("01X", 0, 10));

        assertEquals(ErrorCode.INTERNAL, e.code());
        assertTrue(e.getMessage().contains("coordinate"), e.getMessage());
    }

    @Test
    void committedEvidenceBecomesVisible() {
        repository.insert(run("01W", null, 0L, 0L));

        RunRepository.EvidenceWriter writer = repository.openEvidence("01W");
        writer.pool(new RunRepository.PoolRecord("st1", "[\"P1\"]", 100L, 100L, 2));
        writer.result(new RunSink.ResultLine("st1", "row-1", new Coord(new String[]{"P1", "A1"}), 60L));
        writer.commit();
        writer.close();

        assertEquals(1, repository.pools("01W").size());
        assertEquals(1, repository.resultCount("01W"));
    }

    @Test
    void uncommittedEvidenceIsDiscardedRatherThanLeftHalfWritten() {
        repository.insert(run("01DISCARD", null, 0L, 0L));

        RunRepository.EvidenceWriter writer = repository.openEvidence("01DISCARD");
        writer.pool(new RunRepository.PoolRecord("st1", "[\"P1\"]", 100L, 100L, 2));
        writer.result(new RunSink.ResultLine("st1", "row-1", new Coord(new String[]{"P1", "A1"}), 60L));
        // No commit: this is what a run that failed mid-stage looks like.
        writer.close();

        assertEquals(0, repository.pools("01DISCARD").size(),
                "a failed run must not leave evidence that looks like an answer");
        assertEquals(0, repository.resultCount("01DISCARD"));
    }

    @Test
    void evidenceSpanningSeveralBatchesKeepsOrderAndCount() {
        repository.insert(run("01BATCH", null, 0L, 0L));

        RunRepository.EvidenceWriter writer = repository.openEvidence("01BATCH");
        int rows = 2_500; // more than two batch flushes at 1000 rows
        for (int i = 0; i < rows; i++) {
            writer.result(new RunSink.ResultLine("st1", "row-" + i,
                    new Coord(new String[]{"P1", "A" + i}), i));
        }
        writer.commit();
        writer.close();

        assertEquals(rows, repository.resultCount("01BATCH"));
        List<RunSink.ResultLine> first = repository.results("01BATCH", 0, 3);
        assertEquals(List.of("row-0", "row-1", "row-2"),
                first.stream().map(RunSink.ResultLine::rowId).toList());
        List<RunSink.ResultLine> tail = repository.results("01BATCH", rows - 2, 2);
        assertEquals(List.of("row-2498", "row-2499"),
                tail.stream().map(RunSink.ResultLine::rowId).toList());
    }

    @Test
    void readingAnUnknownRunYieldsNothingRatherThanAnError() {
        Optional<RunRepository.RunRecord> none = repository.find("does-not-exist");

        assertTrue(none.isEmpty());
        assertEquals(List.of(), repository.pools("does-not-exist"));
        assertEquals(List.of(), repository.lines("does-not-exist"));
        assertEquals(0, repository.resultCount("does-not-exist"));
    }

    // ---------------------------------------------------------------- helpers

    private static RunRepository.RunRecord run(String runId, String key, long input, long output) {
        return new RunRepository.RunRecord(runId, "rule-α", "sha-rule", key, "sha-request", 2,
                RunRepository.Status.PUBLISHED, input, output, 3, 42L, null, null,
                1_700_000_000_000L, 1_700_000_001_000L);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
