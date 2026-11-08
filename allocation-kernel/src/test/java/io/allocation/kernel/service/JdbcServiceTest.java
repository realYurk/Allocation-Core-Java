package io.allocation.kernel.service;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the service against a real database.
 *
 * <p>The in-memory repository tests prove the service computes the right answer; this one proves the
 * answer survives the trip through SQL. They are different claims, and the second one is where
 * persistence bugs hide: a coordinate that loses its unicode on the way out, a paging query that
 * silently reorders, a run published before its rows committed.
 *
 * <p>Every assertion is made after reading back from the database, never from the object the service
 * happened to hold.
 */
class JdbcServiceTest {

    private static final String RULE = """
            {
              "schema": "allocation-ir/2.0",
              "rounding_policy": "LARGEST_REMAINDER_IDENTITY",
              "name": "jdbc service test",
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

    @TempDir
    Path directory;

    private JdbcRunRepository repository;
    private DefaultAllocationTemplate template;

    @BeforeEach
    void setUp() throws IOException, SQLException {
        Files.writeString(directory.resolve("rule.json"), RULE, StandardCharsets.UTF_8);
        amounts("P1,SRC,100.00\n");
        Files.writeString(directory.resolve("drivers.csv"), "plant,account,weight\nP1,A1,1\nP1,A2,1\n",
                StandardCharsets.UTF_8);
        repository = new JdbcRunRepository(SchemaFixture.freshDatabase(directory));
        template = new DefaultAllocationTemplate(repository, new DirectoryRuleResolver(directory),
                directory.resolve("work"));
    }

    @AfterEach
    void tearDown() {
        template.close();
    }

    @Test
    void publishesResultsThatSurviveTheTripThroughSql() {
        String runId = submit();

        RunResult result = template.result(runId, 1, 10);

        assertEquals(2, result.total());
        assertEquals(2, result.lines().size());
        assertEquals(Map.of("plant", "P1", "account", "A1"), result.lines().get(0).coord());
        assertEquals("50.00", result.lines().get(0).amount().format());
        assertEquals("50.00", result.lines().get(1).amount().format());
        assertTrue(result.conservation().balanced());
        assertEquals("100.00", result.conservation().input().format());
    }

    @Test
    void reportsTheRunAsPublishedWithItsPoolEvidence() {
        String runId = submit();

        RunView view = template.status(runId);

        assertEquals(RunRepository.Status.PUBLISHED, view.status());
        assertEquals(1, view.progress().totalPools());
        assertEquals(1, view.progress().pools());
        assertEquals(2L, view.progress().rows());
        assertEquals(1, repository.pools(runId).size());
        assertEquals(100_00L, repository.pools(runId).get(0).inputAmount());
    }

    @Test
    void explainReconstructsTheDecisionFromTheStoredLedger() {
        String runId = submit();

        Explain explain = template.explain(runId, Map.of("plant", "P1", "account", "A1"));

        assertEquals(1, explain.steps().size());
        Explain.Step step = explain.steps().get(0);
        assertEquals("st1", step.stageId());
        assertEquals("r1", step.ruleId());
        assertEquals("100.00", step.poolAmount().format());
        assertEquals("100.00", step.shareAmount().format());
        assertEquals(1_000_000L, step.weight());
        assertEquals(2_000_000L, step.denominator());
        assertEquals("50.00", step.allocatedAmount().format());
    }

    @Test
    void replayingAnIdempotencyKeyReturnsTheOriginalRunWithoutRecalculating() {
        String key = "2026-01-close";
        RunCommand command = RunCommand.builder().ruleCode("rule").source(new AmountSource.Csv(directory))
                .idempotencyKey(key).build();

        AllocationTemplate.SubmitResult first = template.submit(command);
        template.await(first.runId(), Duration.ofSeconds(10));
        AllocationTemplate.SubmitResult second = template.submit(command);

        assertEquals(first.runId(), second.runId());
        assertFalse(first.idempotentReplay());
        assertTrue(second.idempotentReplay(), "the second submission must be answered by the first run");
        assertEquals(2, repository.resultCount(first.runId()));
    }

    @Test
    void sameKeyWithDifferentFileContentIsAConflictRatherThanASilentReuse() throws IOException {
        RunCommand command = RunCommand.builder().ruleCode("rule").source(new AmountSource.Csv(directory))
                .idempotencyKey("k").build();
        // Wait for the first run to finish before touching the file: otherwise the assertion could pass
        // or fail depending on whether the reader had already opened it.
        awaitTerminal(template.submit(command).runId());

        amounts("P1,SRC,200.00\n");

        AllocationException e = assertThrows(AllocationException.class, () -> template.submit(command));

        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, e.code(),
                "a re-exported file is different input, so the key must not replay the old answer");
    }

    @Test
    void reusingAKeyOnUnchangedInputReplaysInsteadOfConflicting() throws IOException {
        RunCommand command = RunCommand.builder().ruleCode("rule").source(new AmountSource.Csv(directory))
                .idempotencyKey("k").build();
        String first = template.submit(command).runId();
        awaitTerminal(first);

        // Rewriting the same bytes is not a different request; this is the case a content digest must
        // still treat as a replay, which a naive timestamp or size comparison would get wrong.
        amounts("P1,SRC,100.00\n");

        AllocationTemplate.SubmitResult second = template.submit(command);
        assertEquals(first, second.runId());
        assertTrue(second.idempotentReplay());
    }

    @Test
    void aFailedRunIsRecordedWithItsCodeAndPublishesNothing() throws IOException {
        // P2 has no driver rows, so the pool has no eligible target: the run must fail, not answer zero.
        amounts("P2,SRC,100.00\n");

        String runId = submit();

        RunRepository.RunRecord record = repository.find(runId).orElseThrow();
        assertEquals(RunRepository.Status.FAILED, record.status());
        assertEquals(ErrorCode.NO_ELIGIBLE_DRIVERS.code(), record.failureCode());
        assertEquals(0, repository.resultCount(runId));

        AllocationException e = assertThrows(AllocationException.class, () -> template.result(runId, 1, 10));
        assertEquals(ErrorCode.RESULT_NOT_PUBLISHED, e.code());
    }

    @Test
    void pagingBeyondTheEndIsEmptyRatherThanAnError() {
        String runId = submit();

        assertTrue(template.result(runId, 2, 10).lines().isEmpty());
        assertEquals(2, template.result(runId, 2, 10).total());
    }

    // ---------------------------------------------------------------- helpers

    /** Submits a run over the files currently on disk and waits for it to reach a terminal state. */
    private String submit() {
        AllocationTemplate.SubmitResult submitted = template.submit(RunCommand.builder()
                .ruleCode("rule")
                .source(new AmountSource.Csv(directory))
                .build());
        return awaitTerminal(submitted.runId());
    }

    /**
     * Waits for a terminal status without going through {@code await}, which returns a result and
     * therefore throws for a failed run. A test that wants to inspect a failure needs the record.
     */
    private String awaitTerminal(String runId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            RunRepository.Status status = repository.find(runId).orElseThrow().status();
            if (status.terminal()) {
                return runId;
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

    private void amounts(String body) throws IOException {
        Files.writeString(directory.resolve("amounts.csv"), "plant,account,amount\n" + body,
                StandardCharsets.UTF_8);
    }
}
