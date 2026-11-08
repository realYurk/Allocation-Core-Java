package io.allocation.kernel.service;

import io.allocation.kernel.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifecycle a host is told about.
 *
 * <p>These assertions are about what an audit trail needs to be worth having: every run ends in exactly
 * one terminal event, the payload carries the numbers the record carries, and a listener's own failure is
 * not allowed to become the run's failure.
 */
class AllocationListenerTest {

    @TempDir
    Path directory;

    private final List<AllocationListener.Event> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(directory.resolve("rule.json"), TwoPoolRule.JSON, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("amounts.csv"),
                "plant,account,amount\nP1,SRC,100.00\nP2,SRC,200.00\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("drivers.csv"),
                "plant,account,weight\nP1,A1,1\nP1,A2,1\nP2,A1,1\nP2,A2,1\n", StandardCharsets.UTF_8);
    }

    @Test
    void aPublishedRunReportsSubmissionStartAndItsOwnTotals() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        DefaultAllocationTemplate template = template(repository, events::add);
        try {
            AllocationTemplate.SubmitResult submitted = submit(template, null);
            template.await(submitted.runId(), Duration.ofSeconds(10));

            assertEquals(List.of("Submitted", "Started", "Published"), names(),
                    "every run reports exactly these three, in this order");
            AllocationListener.Published published =
                    assertInstanceOf(AllocationListener.Published.class, events.get(2));
            RunRepository.RunRecord record = repository.find(submitted.runId()).orElseThrow();
            assertEquals(record.inputAmount(), published.inputAmount());
            assertEquals(record.outputAmount(), published.outputAmount());
            assertEquals(record.poolCount(), published.poolCount());
            assertEquals(record.rowCount(), published.rowCount());
            assertEquals(30_000L, published.inputAmount(), "300.00 at precision 2");
            assertEquals(30_000L, published.outputAmount(), "conservation is the point");
        } finally {
            template.close();
        }
    }

    @Test
    void aFailedRunReportsTheCodeThatExplainsIt() {
        // P2 loses its drivers, so its pool has nothing eligible to allocate to.
        write("drivers.csv", "plant,account,weight\nP1,A1,1\nP1,A2,1\n");
        InMemoryRunRepository repository = new InMemoryRunRepository();
        DefaultAllocationTemplate template = template(repository, events::add);
        try {
            AllocationTemplate.SubmitResult submitted = submit(template, null);
            waitForTerminal(repository, submitted.runId());

            AllocationListener.Failed failed =
                    assertInstanceOf(AllocationListener.Failed.class, events.get(events.size() - 1),
                            "the last event of a failed run must be Failed, was " + names());
            assertEquals(ErrorCode.NO_ELIGIBLE_DRIVERS, failed.code());
            assertEquals(0, repository.resultCount(submitted.runId()));
        } finally {
            template.close();
        }
    }

    @Test
    void aCancelledRunIsNotReportedAsAFailure() {
        BlockingRepository repository = BlockingRepository.blockOnPool(1);
        DefaultAllocationTemplate template = template(repository, events::add);
        try {
            AllocationTemplate.SubmitResult submitted = submit(template, null);
            assertTrue(repository.awaitReached());
            assertTrue(template.cancel(submitted.runId()));
            repository.release();

            RunRepository.RunRecord record = waitForTerminal(repository, submitted.runId());
            assertEquals(RunRepository.Status.CANCELLED, record.status());
            assertInstanceOf(AllocationListener.Cancelled.class, events.get(events.size() - 1));
            assertTrue(events.stream().noneMatch(e -> e instanceof AllocationListener.Failed),
                    "a cancellation is somebody changing their mind, not a fault: " + names());
        } finally {
            template.close();
        }
    }

    @Test
    void aListenerThatThrowsDoesNotFailTheRun() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        DefaultAllocationTemplate template = template(repository, event -> {
            throw new IllegalStateException("metrics exporter is down");
        });
        try {
            AllocationTemplate.SubmitResult submitted = submit(template, null);
            template.await(submitted.runId(), Duration.ofSeconds(10));
            assertEquals(RunRepository.Status.PUBLISHED,
                    repository.find(submitted.runId()).orElseThrow().status());
        } finally {
            template.close();
        }
    }

    @Test
    void anIdempotentReplayIsMarkedAsOneAndDoesNotStartASecondRun() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        DefaultAllocationTemplate template = template(repository, events::add);
        try {
            AllocationTemplate.SubmitResult first = submit(template, "key-1");
            template.await(first.runId(), Duration.ofSeconds(10));

            AllocationTemplate.SubmitResult second = submit(template, "key-1");
            assertEquals(first.runId(), second.runId());
            assertTrue(second.idempotentReplay());

            List<AllocationListener.Event> submitted = events.stream()
                    .filter(e -> e instanceof AllocationListener.Submitted).toList();
            assertEquals(2, submitted.size(), "one per request, so a retry is visible: " + names());
            assertFalse(((AllocationListener.Submitted) submitted.get(0)).idempotentReplay());
            assertTrue(((AllocationListener.Submitted) submitted.get(1)).idempotentReplay());
            assertEquals(1, events.stream().filter(e -> e instanceof AllocationListener.Started).count(),
                    "the replay did not execute anything");
        } finally {
            template.close();
        }
    }

    @Test
    void theDefaultTemplateNotifiesNobodyAndStillWorks() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        DefaultAllocationTemplate template = new DefaultAllocationTemplate(repository,
                new DirectoryRuleResolver(directory), directory.resolve("work"));
        try {
            AllocationTemplate.SubmitResult submitted = submit(template, null);
            template.await(submitted.runId(), Duration.ofSeconds(10));
            assertTrue(events.isEmpty(), "the shared no-op listener must not be replaced by a recorder");
        } finally {
            template.close();
        }
    }

    @Test
    void severalListenersEachSeeEveryEvent() {
        List<AllocationListener.Event> second = new CopyOnWriteArrayList<>();
        InMemoryRunRepository repository = new InMemoryRunRepository();
        DefaultAllocationTemplate template = template(repository,
                AllocationListener.allOf(List.of(events::add, second::add)));
        try {
            AllocationTemplate.SubmitResult submitted = submit(template, null);
            template.await(submitted.runId(), Duration.ofSeconds(10));

            assertEquals(List.of("Submitted", "Started", "Published"), names());
            assertEquals(names(), second.stream().map(e -> e.getClass().getSimpleName()).toList());
        } finally {
            template.close();
        }
    }

    @Test
    void aFailingListenerDoesNotSilenceTheOnesAfterIt() {
        // The reason allOf exists instead of a lambda that calls each in turn: a naive fan-out stops at the
        // first exception, so one broken audit hook silently takes the metrics down with it.
        List<AllocationListener.Event> survivor = new CopyOnWriteArrayList<>();
        InMemoryRunRepository repository = new InMemoryRunRepository();
        DefaultAllocationTemplate template = template(repository, AllocationListener.allOf(List.of(
                event -> {
                    throw new IllegalStateException("audit table is gone");
                },
                survivor::add)));
        try {
            AllocationTemplate.SubmitResult submitted = submit(template, null);
            template.await(submitted.runId(), Duration.ofSeconds(10));

            assertEquals(List.of("Submitted", "Started", "Published"),
                    survivor.stream().map(e -> e.getClass().getSimpleName()).toList());
        } finally {
            template.close();
        }
    }

    @Test
    void allOfCollapsesToTheNoopInstanceWhenNobodyIsListening() {
        // Identity matters: the template compares against the shared no-op to skip the notify path
        // entirely, so a composed listener must be that exact instance rather than a do-nothing wrapper.
        assertSame(AllocationListener.noop(), AllocationListener.allOf(List.of()));
        assertSame(AllocationListener.noop(),
                AllocationListener.allOf(List.of(AllocationListener.noop(), AllocationListener.noop())));

        AllocationListener one = event -> {
        };
        assertSame(one, AllocationListener.allOf(List.of(one)),
                "a single listener must not be wrapped in a composite for nothing");
    }

    // ------------------------------------------------------------------ helpers

    private DefaultAllocationTemplate template(RunRepository repository, AllocationListener listener) {
        return new DefaultAllocationTemplate(repository, new DirectoryRuleResolver(directory),
                directory.resolve("work"), 16, listener);
    }

    private AllocationTemplate.SubmitResult submit(DefaultAllocationTemplate template, String key) {
        return template.submit(RunCommand.builder()
                .ruleCode("rule")
                .source(new AmountSource.Csv(directory))
                .idempotencyKey(key)
                .options(new RunCommand.Options(1, true, false))
                .build());
    }

    private List<String> names() {
        return events.stream().map(e -> e.getClass().getSimpleName()).toList();
    }

    private void write(String name, String content) {
        try {
            Files.writeString(directory.resolve(name), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
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
