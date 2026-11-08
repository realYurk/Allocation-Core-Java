package io.allocation.kernel.service;

import io.allocation.kernel.exec.RunSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stopping a run, and the two things a stop must not do.
 *
 * <p>It must not publish, because a half-computed result set is indistinguishable from a complete one to
 * whoever reads it next. And it must not pretend to succeed when it cannot: a run this process does not
 * own returns {@code false} rather than a cheerful {@code true} followed by nothing happening.
 *
 * <p>The run is stopped at a known point rather than by racing it. The repository below blocks the first
 * evidence line, which is inside pool 1 of 2, so the stop order arrives with certainty at a moment when
 * the executor is mid-stage — and the next pool boundary is where it must be honoured.
 */
class CancellationTest {

    private static final String RULE = """
            {
              "schema": "allocation-ir/2.0",
              "rounding_policy": "LARGEST_REMAINDER_IDENTITY",
              "name": "cancel test",
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

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(directory.resolve("rule.json"), RULE, StandardCharsets.UTF_8);
        // Two pools, so there is a boundary after the one that gets blocked.
        Files.writeString(directory.resolve("amounts.csv"),
                "plant,account,amount\nP1,SRC,100.00\nP2,SRC,200.00\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("drivers.csv"),
                "plant,account,weight\nP1,A1,1\nP1,A2,1\nP2,A1,1\nP2,A2,1\n", StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void cancelStopsTheRunAndPublishesNothing() {
        BlockingRepository repository = new BlockingRepository();
        DefaultAllocationTemplate template = new DefaultAllocationTemplate(repository,
                new DirectoryRuleResolver(directory), directory.resolve("work"));
        try {
            AllocationTemplate.SubmitResult submitted = template.submit(RunCommand.builder()
                    .ruleCode("rule")
                    .source(new AmountSource.Csv(directory))
                    .options(new RunCommand.Options(1, true, false))
                    .build());

            assertTrue(repository.awaitFirstLine(), "the run never reached its evidence writer");
            assertTrue(template.cancel(submitted.runId()), "a running run must accept a stop order");
            repository.release();

            RunRepository.RunRecord record = waitForTerminal(repository, submitted.runId());
            assertEquals(RunRepository.Status.CANCELLED, record.status());
            assertTrue(record.failureCode() == null, "a cancellation is not a failure: " + record.failureCode());
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

    /**
     * Repository that parks the run on its first evidence line, so a stop order can be timed exactly.
     *
     * <p>A decorator rather than a subclass, because {@link InMemoryRunRepository} is {@code final} on
     * purpose: a storage implementation that expects to be extended is a storage implementation whose
     * invariants can be broken from outside.
     */
    private static final class BlockingRepository implements RunRepository {

        private final InMemoryRunRepository delegate = new InMemoryRunRepository();
        private final CountDownLatch firstLine = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicBoolean armed = new AtomicBoolean(true);

        @Override
        public EvidenceWriter openEvidence(String runId) {
            EvidenceWriter inner = delegate.openEvidence(runId);
            return new EvidenceWriter() {
                @Override
                public void pool(PoolRecord record) {
                    inner.pool(record);
                }

                @Override
                public void line(RunSink.LedgerLine line) {
                    if (armed.compareAndSet(true, false)) {
                        firstLine.countDown();
                        awaitRelease();
                    }
                    inner.line(line);
                }

                @Override
                public void result(RunSink.ResultLine line) {
                    inner.result(line);
                }

                @Override
                public void commit() {
                    inner.commit();
                }

                @Override
                public void close() {
                    inner.close();
                }
            };
        }

        @Override
        public java.util.Optional<RunRecord> find(String runId) {
            return delegate.find(runId);
        }

        @Override
        public java.util.Optional<RunRecord> findByIdempotencyKey(String idempotencyKey) {
            return delegate.findByIdempotencyKey(idempotencyKey);
        }

        @Override
        public void insert(RunRecord run) {
            delegate.insert(run);
        }

        @Override
        public void update(RunRecord run) {
            delegate.update(run);
        }

        @Override
        public void savePools(String runId, java.util.List<PoolRecord> pools) {
            delegate.savePools(runId, pools);
        }

        @Override
        public void saveLines(String runId, java.util.List<RunSink.LedgerLine> lines) {
            delegate.saveLines(runId, lines);
        }

        @Override
        public void saveResults(String runId, java.util.List<RunSink.ResultLine> results) {
            delegate.saveResults(runId, results);
        }

        @Override
        public java.util.List<PoolRecord> pools(String runId) {
            return delegate.pools(runId);
        }

        @Override
        public java.util.List<RunSink.LedgerLine> lines(String runId) {
            return delegate.lines(runId);
        }

        @Override
        public java.util.List<RunSink.ResultLine> results(String runId, int offset, int limit) {
            return delegate.results(runId, offset, limit);
        }

        @Override
        public int resultCount(String runId) {
            return delegate.resultCount(runId);
        }

        boolean awaitFirstLine() {
            try {
                return firstLine.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        void release() {
            released.countDown();
        }

        private void awaitRelease() {
            try {
                released.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
