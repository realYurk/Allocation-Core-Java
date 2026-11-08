package io.allocation.kernel.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a poller sees while a run is still going.
 *
 * <p>This is a contract question, not a cosmetic one: {@code docs/API.md} §2.3 promises
 * {@code pools / totalPools / rows} during a run, and before this existed a ten-minute job reported zero
 * from submission to completion — indistinguishable, from the outside, from a request that hung.
 *
 * <p>The figures are observed at a point the test controls: the run is parked on the first evidence line
 * of the second pool, which is after pool 1 has been allocated and before pool 2 has emitted anything.
 */
class ProgressReportingTest {

    @TempDir
    Path directory;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(directory.resolve("rule.json"), TwoPoolRule.JSON, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("amounts.csv"),
                "plant,account,amount\nP1,SRC,100.00\nP2,SRC,200.00\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("drivers.csv"),
                "plant,account,weight\nP1,A1,1\nP1,A2,1\nP2,A1,1\nP2,A2,1\n", StandardCharsets.UTF_8);
    }

    @Test
    void aRunningRunReportsItsPoolsAndRows() {
        BlockingRepository repository = BlockingRepository.blockOnPool(2);
        DefaultAllocationTemplate template = new DefaultAllocationTemplate(repository,
                new DirectoryRuleResolver(directory), directory.resolve("work"));
        try {
            AllocationTemplate.SubmitResult submitted = template.submit(RunCommand.builder()
                    .ruleCode("rule")
                    .source(new AmountSource.Csv(directory))
                    .options(new RunCommand.Options(1, true, false))
                    .build());

            assertTrue(repository.awaitReached(), "the run never reached its second pool");

            RunView running = template.status(submitted.runId());
            assertEquals(RunRepository.Status.RUNNING, running.status());
            assertEquals(2, running.progress().totalPools(), "the denominator must be known up front");
            assertEquals(1, running.progress().pools(), "pool 1 finished, pool 2 has not");
            assertEquals("st1", running.progress().stageId(),
                    "the counters belong to a stage; without the name a two-stage run looks like it "
                            + "restarts when the second stage begins");
            assertTrue(running.progress().rows() >= 2,
                    "rows must reflect what has been written, was " + running.progress().rows());

            repository.release();
            template.await(submitted.runId(), Duration.ofSeconds(10));

            RunView published = template.status(submitted.runId());
            assertEquals(RunRepository.Status.PUBLISHED, published.status());
            assertEquals(2, published.progress().pools());
            assertEquals(4, published.progress().rows());
            assertNull(published.progress().stageId(),
                    "a finished run's numbers are the run's own, summed over every stage");
        } finally {
            template.close();
        }
    }

    @Test
    void aFinishedRunNeverReportsMorePoolsThanItRecorded() {
        // The state a killed process leaves behind: evidence committed, the run row never updated with the
        // counts. Counting the evidence would report "3 of 0" — a progress bar at 300% — and would do it on
        // every poll of every finished run, which is the cost this shape exists to avoid: a UI refreshing a
        // 100,000-pool run would pull 100,000 rows to display a number the run row already holds.
        InMemoryRunRepository repository = new InMemoryRunRepository();
        long now = System.currentTimeMillis();
        RunRepository.RunRecord run = new RunRepository.RunRecord("abandoned", "rule", "sha", null, "req", 2,
                RunRepository.Status.PENDING, 0L, 0L, 0, 0L, null, null, now, null);
        repository.insert(run);
        repository.savePools("abandoned", java.util.List.of(
                new RunRepository.PoolRecord("st1", "P1", 100_00L, 100_00L, 2),
                new RunRepository.PoolRecord("st1", "P2", 100_00L, 100_00L, 2),
                new RunRepository.PoolRecord("st1", "P3", 100_00L, 100_00L, 2)));
        repository.update(run.failed("ALLOC_012", "process died", now));

        DefaultAllocationTemplate template = new DefaultAllocationTemplate(repository,
                new DirectoryRuleResolver(directory), directory.resolve("work"));
        try {
            RunView view = template.status("abandoned");

            assertEquals(RunRepository.Status.FAILED, view.status());
            assertEquals(0, view.progress().pools(),
                    "the record says nothing was counted, so nothing is what it reports");
            assertEquals(0, view.progress().totalPools());
        } finally {
            template.close();
        }
    }

    @Test
    void aQueuedRunReportsNothingRatherThanFailing() {        // The run is submitted against a rule that does not exist, so it never starts; status must still
        // answer, because a poller that gets an error for a run it just submitted has no recovery path.
        InMemoryRunRepository repository = new InMemoryRunRepository();
        repository.insert(new RunRepository.RunRecord("queued", "rule", "sha", null, "req", 2,
                RunRepository.Status.PENDING, 0L, 0L, 0, 0L, null, null, System.currentTimeMillis(), null));
        DefaultAllocationTemplate template = new DefaultAllocationTemplate(repository,
                new DirectoryRuleResolver(directory), directory.resolve("work"));
        try {
            RunView view = template.status("queued");

            assertEquals(RunRepository.Status.PENDING, view.status());
            assertEquals(0, view.progress().pools());
            assertEquals(0, view.progress().totalPools());
            assertEquals(0L, view.progress().rows());
            assertNull(view.progress().stageId(), "a run that has not started has no stage to name");
        } finally {
            template.close();
        }
    }
}
