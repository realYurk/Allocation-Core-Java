package io.allocation.kernel.service;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.reconcile.Reconciler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the service layer the way a host application would: by rule code, with rows in hand.
 *
 * <p>The rule below is intentionally the smallest one that still allocates (one pool, two targets,
 * equal weights), so an assertion failure points at the service rather than at the planner.
 */
class DefaultAllocationTemplateTest {

    private static final String RULE = """
            {
              "schema": "allocation-ir/2.0",
              "rounding_policy": "LARGEST_REMAINDER_IDENTITY",
              "name": "service test",
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

    private InMemoryRunRepository repository;
    private DefaultAllocationTemplate template;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(directory.resolve("rule.json"), RULE, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("weighted.json"), RULE
                .replace("\"name\": \"service test\"", "\"name\": \"weighted test\"")
                .replace("\"drivers.csv\"", "\"drivers2.csv\""), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("amounts.csv"), "plant,account,amount\nP1,SRC,100.00\n",
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("drivers.csv"), "plant,account,weight\nP1,A1,1\nP1,A2,1\n",
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("drivers2.csv"), "plant,account,weight\nP1,A1,1\nP1,A2,2\n",
                StandardCharsets.UTF_8);
        repository = new InMemoryRunRepository();
        template = new DefaultAllocationTemplate(repository, new DirectoryRuleResolver(directory),
                directory.resolve("work"));
    }

    @AfterEach
    void tearDown() {
        template.close();
    }

    private static AmountRow row(String plant, String account, String amount) {
        return new AmountRow(Map.of("plant", plant, "account", account), Money.parse(amount, 2));
    }

    private Preview preview(String amount) {
        return template.preview(PreviewCommand.builder()
                .ruleCode("rule")
                .rows(List.of(row("P1", "SRC", amount)))
                .explain(true)
                .build());
    }

    @Test
    void previewSplitsEvenlyAndConserves() {
        Preview preview = preview("100.00");

        assertEquals(2, preview.lines().size());
        assertEquals(List.of("50.00", "50.00"),
                preview.lines().stream().map(line -> line.amount().format()).toList());
        assertTrue(preview.conservation().balanced());
        assertEquals("100.00", preview.conservation().input().format());
        assertEquals("100.00", preview.conservation().output().format());
        assertEquals(List.of("A1", "A2"),
                preview.lines().stream().map(line -> line.coord().get("account")).toList());
    }

    @Test
    void previewExplainsWeightDenominatorAndRank() {
        Preview preview = preview("100.00");

        Preview.ShareTrace trace = preview.lines().get(0).trace();
        assertNotNull(trace);
        assertEquals("100.00", trace.poolAmount().format());
        assertEquals(1_000_000L, trace.weight());
        assertEquals(2_000_000L, trace.denominator());
        assertEquals(0, trace.remainderRank());
        assertFalse(trace.gotResidual());
        assertEquals(1, preview.lines().get(1).trace().remainderRank());
    }

    @Test
    void previewMarksTheResidualRecipient() {
        // Weighted 1:2 over 100.00 gives 33.333... and 66.666..., so one cent is settled by largest
        // remainder and lands on the second target.
        Preview preview = template.preview(PreviewCommand.builder()
                .ruleCode("weighted")
                .rows(List.of(row("P1", "SRC", "100.00")))
                .explain(true)
                .build());

        assertEquals(List.of("33.33", "66.67"),
                preview.lines().stream().map(line -> line.amount().format()).toList());
        assertFalse(preview.lines().get(0).trace().gotResidual());
        assertTrue(preview.lines().get(1).trace().gotResidual());
        assertEquals(0, preview.lines().get(1).trace().remainderRank());
    }

    @Test
    void previewWithoutExplainOmitsTheTrace() {
        Preview preview = template.preview(PreviewCommand.builder()
                .ruleCode("rule")
                .rows(List.of(row("P1", "SRC", "100.00")))
                .build());

        assertNull(preview.lines().get(0).trace());
    }

    @Test
    void previewRejectsAnUnknownDimensionInARow() {
        AllocationException e = assertThrows(AllocationException.class, () -> template.preview(
                PreviewCommand.builder().ruleCode("rule")
                        .rows(List.of(new AmountRow(Map.of("plant", "P1"), Money.parse("1.00", 2))))
                        .build()));

        assertEquals(ErrorCode.RULE_INVALID, e.code());
        assertTrue(e.getMessage().contains("account"), e.getMessage());
    }

    @Test
    void previewRejectsTooManyRows() {
        List<AmountRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i <= PreviewCommand.MAX_ROWS; i++) {
            rows.add(row("P1", "SRC", "1.00"));
        }

        AllocationException e = assertThrows(AllocationException.class, () -> template.preview(
                PreviewCommand.builder().ruleCode("rule").rows(rows).build()));

        assertEquals(ErrorCode.REQUEST_TOO_LARGE, e.code());
    }

    @Test
    void submitRunsToCompletionAndPublishes() {
        AllocationTemplate.SubmitResult submitted = template.submit(RunCommand.builder()
                .ruleCode("rule")
                .source(new AmountSource.Csv(directory))
                .idempotencyKey("2026-01")
                .build());

        assertFalse(submitted.idempotentReplay());
        assertEquals(RunRepository.Status.PENDING, submitted.status());

        RunResult result = template.await(submitted.runId(), Duration.ofSeconds(10));
        assertEquals(RunRepository.Status.PUBLISHED, result.status());
        assertEquals(2, result.total());
        assertTrue(result.conservation().balanced());
        assertEquals("100.00", result.conservation().input().format());
        assertEquals(1, template.status(submitted.runId()).progress().pools());
    }

    @Test
    void repeatingAnIdempotencyKeyReturnsTheOriginalRun() {
        RunCommand command = RunCommand.builder()
                .ruleCode("rule")
                .source(new AmountSource.Csv(directory))
                .idempotencyKey("2026-02")
                .build();

        AllocationTemplate.SubmitResult first = template.submit(command);
        template.await(first.runId(), Duration.ofSeconds(10));
        AllocationTemplate.SubmitResult second = template.submit(command);

        assertEquals(first.runId(), second.runId());
        assertTrue(second.idempotentReplay());
        assertEquals(RunRepository.Status.PUBLISHED, second.status());
    }

    @Test
    void reusingAKeyForDifferentInputIsAConflict() {
        template.submit(RunCommand.builder()
                .ruleCode("rule")
                .source(new AmountSource.InMemory(List.of(row("P1", "SRC", "100.00"))))
                .idempotencyKey("2026-03")
                .build());

        AllocationException e = assertThrows(AllocationException.class, () -> template.submit(
                RunCommand.builder()
                        .ruleCode("rule")
                        .source(new AmountSource.InMemory(List.of(row("P1", "SRC", "200.00"))))
                        .idempotencyKey("2026-03")
                        .build()));

        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, e.code());
    }

    @Test
    void resultsAreOnlyVisibleOncePublished() {
        repository.insert(new RunRepository.RunRecord("run-pending", "rule", "sha", null, "req", 2,
                RunRepository.Status.PENDING, 0L, 0L, 0, 0L, null, null, System.currentTimeMillis(), null));

        AllocationException e = assertThrows(AllocationException.class, () -> template.result("run-pending", 1, 10));

        assertEquals(ErrorCode.RESULT_NOT_PUBLISHED, e.code());
    }

    @Test
    void explainReturnsTheDecisionForOneCoordinate() {
        AllocationTemplate.SubmitResult submitted = template.submit(RunCommand.builder()
                .ruleCode("rule")
                .source(new AmountSource.Csv(directory))
                .build());
        template.await(submitted.runId(), Duration.ofSeconds(10));

        Explain explain = template.explain(submitted.runId(), Map.of("plant", "P1", "account", "A1"));

        assertEquals(1, explain.steps().size());
        Explain.Step step = explain.steps().get(0);
        assertEquals("st1", step.stageId());
        assertEquals("r1", step.ruleId());
        assertEquals("100.00", step.poolAmount().format());
        assertEquals("100.00", step.shareAmount().format());
        assertEquals(1_000_000L, step.weight());
        assertEquals(2_000_000L, step.denominator());
        assertEquals(0, step.remainderRank());
        assertEquals("50.00", step.allocatedAmount().format());
        assertEquals(64, explain.ruleSha256().length());
    }

    @Test
    void reconcileAgainstAReferenceFile() throws IOException {
        AllocationTemplate.SubmitResult submitted = template.submit(RunCommand.builder()
                .ruleCode("rule")
                .source(new AmountSource.Csv(directory))
                .build());
        template.await(submitted.runId(), Duration.ofSeconds(10));
        Path reference = directory.resolve("reference.csv");
        Files.writeString(reference, "plant,account,amount\nP1,A1,50.00\nP1,A2,50.00\n", StandardCharsets.UTF_8);

        AllocationTemplate.ReconcileOutcome outcome = template.reconcile(ReconcileCommand.builder()
                .runId(submitted.runId()).reference(reference).build());

        assertEquals("MATCH", outcome.report().status());
        assertEquals(2, outcome.report().matched());
        assertTrue(Files.exists(Path.of(outcome.differencesPath())));

        Files.writeString(reference, "plant,account,amount\nP1,A1,50.00\nP1,A2,49.99\n", StandardCharsets.UTF_8);
        AllocationTemplate.ReconcileOutcome differed = template.reconcile(ReconcileCommand.builder()
                .runId(submitted.runId()).reference(reference).tolerance("0").build());

        assertEquals("DIFFERENCES", differed.report().status());
        assertEquals(1, differed.report().differed());
        assertEquals(1L, differed.report().differences().get(0).delta());
    }

    @Test
    void reconcileHonoursTolerance() throws IOException {
        AllocationTemplate.SubmitResult submitted = template.submit(RunCommand.builder()
                .ruleCode("rule")
                .source(new AmountSource.Csv(directory))
                .build());
        template.await(submitted.runId(), Duration.ofSeconds(10));
        Path reference = directory.resolve("reference.csv");
        Files.writeString(reference, "plant,account,amount\nP1,A1,50.00\nP1,A2,49.99\n", StandardCharsets.UTF_8);

        AllocationTemplate.ReconcileOutcome outcome = template.reconcile(ReconcileCommand.builder()
                .runId(submitted.runId()).reference(reference).tolerance("0.01")
                .duplicatePolicy(Reconciler.DuplicatePolicy.AGGREGATE).build());

        assertEquals("MATCH", outcome.report().status());
    }

    @Test
    void aFailedRunIsRecordedAsFailed() throws IOException {
        Files.delete(directory.resolve("drivers.csv"));

        AllocationTemplate.SubmitResult submitted = template.submit(RunCommand.builder()
                .ruleCode("rule")
                .source(new AmountSource.Csv(directory))
                .build());
        RunRepository.RunRecord record = waitForTerminal(submitted.runId());

        assertEquals(RunRepository.Status.FAILED, record.status());
        assertNotNull(record.failureCode());
        assertTrue(record.failureCode().startsWith("ALLOC_"), record.failureCode());
    }

    private RunRepository.RunRecord waitForTerminal(String runId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            RunRepository.RunRecord record = repository.find(runId).orElseThrow();
            if (record.status().terminal()) {
                return record;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("run " + runId + " did not finish");
    }
}
