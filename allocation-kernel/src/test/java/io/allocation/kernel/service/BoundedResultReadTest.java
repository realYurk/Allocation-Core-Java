package io.allocation.kernel.service;

import io.allocation.kernel.exec.RunSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A result page must cost a page, not a run.
 *
 * <p>The service used to read the entire ledger to answer either question — one page of results, or one
 * coordinate's explanation. At the sizes this engine advertises that is not a slow path, it is a wall: a
 * million-row run means a million ledger objects, a few hundred megabytes, per request. The fix reads the
 * page's own lines and then only the pools those lines belong to.
 *
 * <p>The assertion is made by forbidding the wide read: the repository in this test throws from
 * {@link RunRepository#lines(String)}. Anything that goes back to reading the whole ledger fails loudly
 * here rather than quietly in production.
 */
class BoundedResultReadTest {

    @TempDir
    Path directory;

    private InMemoryRunRepository store;
    private DefaultAllocationTemplate template;
    private String runId;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(directory.resolve("rule.json"), TwoPoolRule.JSON, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("amounts.csv"),
                "plant,account,amount\nP1,SRC,100.00\nP2,SRC,200.00\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("drivers.csv"),
                "plant,account,weight\nP1,A1,1\nP1,A2,1\nP2,A1,1\nP2,A2,1\n", StandardCharsets.UTF_8);

        store = new InMemoryRunRepository();
        template = new DefaultAllocationTemplate(new NoWholeLedger(store),
                new DirectoryRuleResolver(directory), directory.resolve("work"));
        AllocationTemplate.SubmitResult submitted = template.submit(RunCommand.builder()
                .ruleCode("rule")
                .source(new AmountSource.Csv(directory))
                .build());
        template.await(submitted.runId(), Duration.ofSeconds(10));
        runId = submitted.runId();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        template.close();
    }

    @Test
    void aResultPageNeverReadsTheWholeLedger() {
        RunResult result = template.result(runId, 1, 10);

        assertEquals(4, result.lines().size());
        assertEquals("300.00", result.conservation().input().format());
        for (Preview.AllocatedLine line : result.lines()) {
            assertNotNull(line.trace(), "the trace is what makes a line explainable: " + line.coord());
            assertEquals(1_000_000L, line.trace().weight(),
                    "100.00 split evenly across two accounts, weights scaled to six places");
            assertEquals(2_000_000L, line.trace().denominator());
        }
    }

    @Test
    void anExplanationNeverReadsTheWholeLedgerEither() {
        Map<String, String> coord = new LinkedHashMap<>();
        coord.put("plant", "P1");
        coord.put("account", "A1");

        Explain explain = template.explain(runId, coord);

        assertEquals(1, explain.steps().size(), "one stage, one pool, one target");
        Explain.Step step = explain.steps().get(0);
        assertEquals("100.00", step.poolAmount().format());
        assertEquals(50_00L, step.allocatedAmount().unitsAt(2));
        assertFalse(explain.ruleSha256().isEmpty());
    }

    @Test
    void aPagePastTheEndIsEmptyRatherThanAnError() {
        RunResult result = template.result(runId, 5, 10);
        assertTrue(result.lines().isEmpty());
        assertEquals(4, result.total());
    }

    @Test
    void theWideReadStillExistsForCallersThatGenuinelyWantIt() {
        // Not an assertion about the service: it is here so the guard above cannot be mistaken for "the
        // interface lost a method". Reading a run whole remains possible; it is paging that must not.
        assertEquals(4, store.lines(runId).size(), "two pools, two targets each");
    }

    /**
     * Wraps a repository and refuses {@link RunRepository#lines(String)}, while offering the two narrow
     * reads by filtering.
     *
     * <p>Filtering is what the default interface method does, and it is fine here: the point is which
     * method the service asks for, not how the answer is produced.
     */
    private static final class NoWholeLedger implements RunRepository {

        private final RunRepository delegate;

        NoWholeLedger(RunRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<RunSink.LedgerLine> lines(String runId) {
            throw new AssertionError("the service read the whole ledger for run " + runId);
        }

        @Override
        public List<RunSink.LedgerLine> linesForTargets(String runId, Collection<String> targetKeys) {
            return delegate.linesForTargets(runId, targetKeys);
        }

        @Override
        public List<RunSink.LedgerLine> linesForPool(String runId, String stageId, String ruleId,
                                                     String poolKey) {
            return delegate.linesForPool(runId, stageId, ruleId, poolKey);
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
        public void savePools(String runId, List<PoolRecord> pools) {
            delegate.savePools(runId, pools);
        }

        @Override
        public void saveLines(String runId, List<RunSink.LedgerLine> lines) {
            delegate.saveLines(runId, lines);
        }

        @Override
        public void saveResults(String runId, List<RunSink.ResultLine> results) {
            delegate.saveResults(runId, results);
        }

        @Override
        public List<PoolRecord> pools(String runId) {
            return delegate.pools(runId);
        }

        @Override
        public List<RunSink.ResultLine> results(String runId, long offset, int limit) {
            return delegate.results(runId, offset, limit);
        }

        @Override
        public int resultCount(String runId) {
            return delegate.resultCount(runId);
        }

        @Override
        public Optional<RunRecord> find(String runId) {
            return delegate.find(runId);
        }

        @Override
        public Optional<RunRecord> findByIdempotencyKey(String idempotencyKey) {
            return delegate.findByIdempotencyKey(idempotencyKey);
        }

        @Override
        public List<RunRecord> byStatus(Status status, int limit) {
            return delegate.byStatus(status, limit);
        }

        @Override
        public EvidenceWriter openEvidence(String runId) {
            return delegate.openEvidence(runId);
        }
    }
}
