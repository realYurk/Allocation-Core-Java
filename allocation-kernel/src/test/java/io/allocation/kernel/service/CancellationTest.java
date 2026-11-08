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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stopping a run, and the two things a stop must not do.
 *
 * <p>It must not publish, because a half-computed result set is indistinguishable from a complete one to
 * whoever reads it next. And it must not pretend to succeed when it cannot: a run this process does not
 * own returns {@code false} rather than a cheerful {@code true} followed by nothing happening.
 *
 * <p>The run is stopped at a known point rather than by racing it — see {@link BlockingRepository}. The
 * stop order lands inside pool 1 of 2, so the boundary that has to honour it is the next one.
 */
class CancellationTest {

    private static final String RULE = TwoPoolRule.JSON;

    @TempDir
    Path directory;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(directory.resolve("rule.json"), RULE, StandardCharsets.UTF_8);
        // Two pools, so there is a boundary after the one that gets blocked.
        Files.writeString(directory.resolve("amounts.csv"),
                "plant,account,amount\nP1,SRC,100.00\nP2,SRC,200.00\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("drivers.csv"),
                "plant,account,weight\nP1,A1,1\nP1,A2,1\nP2,A1,1\nP2,A2,1\n", StandardCharsets.UTF_8);
    }

    @Test
    void cancelStopsTheRunAndPublishesNothing() {
        BlockingRepository repository = BlockingRepository.blockOnPool(1);
        DefaultAllocationTemplate template = new DefaultAllocationTemplate(repository,
                new DirectoryRuleResolver(directory), directory.resolve("work"));
        try {
            AllocationTemplate.SubmitResult submitted = template.submit(RunCommand.builder()
                    .ruleCode("rule")
                    .source(new AmountSource.Csv(directory))
                    .options(new RunCommand.Options(1, true, false))
                    .build());

            assertTrue(repository.awaitReached(), "the run never reached its evidence writer");
            assertTrue(template.cancel(submitted.runId()), "a running run must accept a stop order");
            repository.release();

            RunRepository.RunRecord record = waitForTerminal(repository, submitted.runId());
            assertEquals(RunRepository.Status.CANCELLED, record.status());
            assertNull(record.failureCode(), "a cancellation is not a failure");
            assertEquals(0, repository.resultCount(submitted.runId()), "a cancelled run must publish nothing");
        } finally {
            template.close();
        }
    }

    @Test
    void cancellingAFinishedRunIsRefused() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        DefaultAllocationTemplate template = new DefaultAllocationTemplate(repository,
                new DirectoryRuleResolver(directory), directory.resolve("work"));
        try {
            AllocationTemplate.SubmitResult submitted = template.submit(RunCommand.builder()
                    .ruleCode("rule")
                    .source(new AmountSource.Csv(directory))
                    .build());
            template.await(submitted.runId(), Duration.ofSeconds(10));

            assertFalse(template.cancel(submitted.runId()));
            assertEquals(RunRepository.Status.PUBLISHED,
                    repository.find(submitted.runId()).orElseThrow().status());
        } finally {
            template.close();
        }
    }

    @Test
    void cancellingAnUnknownRunIsRefused() {
        DefaultAllocationTemplate template = new DefaultAllocationTemplate(new InMemoryRunRepository(),
                new DirectoryRuleResolver(directory), directory.resolve("work"));
        try {
            assertFalse(template.cancel("01NOTARUN"));
        } finally {
            template.close();
        }
    }

    private static RunRepository.RunRecord waitForTerminal(RunRepository repository, String runId) {
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
