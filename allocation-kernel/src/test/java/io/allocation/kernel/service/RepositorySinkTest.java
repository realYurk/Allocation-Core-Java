package io.allocation.kernel.service;

import io.allocation.kernel.coord.Coord;
import io.allocation.kernel.exec.RunSink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the mapping from executor callbacks to evidence rows.
 *
 * <p>The interesting part is derived data: a pool's target count is not something the engine says, it is
 * something the sink infers from the emission order. If that inference is wrong, every pool in every run
 * records the wrong breadth of distribution and nothing else fails — which is exactly the kind of quiet
 * wrongness worth a dedicated test.
 */
class RepositorySinkTest {

    @Test
    void aPoolsTargetCountIsTheLedgerRowsSinceTheLastReconciliation() {
        FakeWriter writer = new FakeWriter();
        RepositorySink sink = new RepositorySink(writer, true);

        // Pool one: three targets.
        sink.ledger(line("st1", "P1", "A1", 100L, 33L));
        sink.ledger(line("st1", "P1", "A2", 100L, 33L));
        sink.ledger(line("st1", "P1", "A3", 100L, 34L));
        sink.reconciliation(pool("st1", "P1", 100L, 100L));
        // Pool two: one target, and the counter must have been reset.
        sink.ledger(line("st1", "P2", "A1", 50L, 50L));
        sink.reconciliation(pool("st1", "P2", 50L, 50L));

        assertEquals(List.of(3, 1), writer.pools.stream().map(RunRepository.PoolRecord::targetCount).toList());
        assertEquals(List.of("[\"P1\"]", "[\"P2\"]"),
                writer.pools.stream().map(RunRepository.PoolRecord::poolKey).toList());
    }

    @Test
    void totalsComeFromTheReconciliationLines() {
        FakeWriter writer = new FakeWriter();
        RepositorySink sink = new RepositorySink(writer, true);

        sink.reconciliation(pool("st1", "P1", 100L, 100L));
        sink.reconciliation(pool("st2", "P1", 100L, 100L));

        assertEquals(200L, sink.inputAmount());
        assertEquals(200L, sink.outputAmount());
        assertEquals(2L, sink.poolRows());
    }

    @Test
    void resultsAreCountedEvenWhenTheyAreNotRetained() {
        FakeWriter writer = new FakeWriter();
        RepositorySink retaining = new RepositorySink(writer, true);
        RepositorySink counting = new RepositorySink(writer, false);

        for (int i = 0; i < 5; i++) {
            RunSink.ResultLine result = result("row-" + i, "A" + i, 1L);
            retaining.result(result);
            counting.result(result);
        }

        assertEquals(5L, retaining.resultRows());
        assertEquals(5L, counting.resultRows(), "the run still reports how many rows it produced");
        assertEquals(5, writer.results.size(), "only the retaining sink writes result rows");
    }

    @Test
    void theLedgerIsNotKeptWhenDetailsAreNotRetainedButPoolEvidenceAlwaysIs() {
        FakeWriter writer = new FakeWriter();
        RepositorySink sink = new RepositorySink(writer, false);

        sink.ledger(line("st1", "P1", "A1", 10L, 10L));
        sink.reconciliation(pool("st1", "P1", 10L, 10L));

        assertTrue(writer.lines.isEmpty(), "detail rows are optional");
        assertEquals(1, writer.pools.size(), "the balance proof is not optional");
        assertEquals(1, writer.pools.get(0).targetCount());
    }

    @Test
    void membershipAndLineageAreNotPersistedPerRow() {
        FakeWriter writer = new FakeWriter();
        RepositorySink sink = new RepositorySink(writer, true);

        sink.membership(new RunSink.MembershipLine("st1", "[\"P1\"]", "row-1", 10L));
        sink.lineage(new RunSink.LineageLine("st1", "SPLIT", "row-1", "row-2", 10L));

        assertEquals(0, writer.pools.size() + writer.lines.size() + writer.results.size());
    }

    // ---------------------------------------------------------------- helpers

    private static RunSink.LedgerLine line(String stage, String plant, String account, long share, long allocated) {
        return new RunSink.LedgerLine(stage, "r1", plantKey(plant), targetKey(plant, account), "row-" + account,
                share, share, 1_000_000L, 2_000_000L, allocated);
    }

    private static RunSink.ReconciliationLine pool(String stage, String plant, long input, long output) {
        return new RunSink.ReconciliationLine(stage, plantKey(plant), input, output, 0L, "PASS");
    }

    private static RunSink.ResultLine result(String rowId, String account, long amount) {
        return new RunSink.ResultLine("st1", rowId, new Coord(new String[]{"P1", account}), amount);
    }

    private static String plantKey(String plant) {
        return new Coord(new String[]{plant}).key();
    }

    private static String targetKey(String plant, String account) {
        return new Coord(new String[]{plant, account}).key();
    }

    /** Records what a real writer would persist. */
    private static final class FakeWriter implements RunRepository.EvidenceWriter {
        private final List<RunRepository.PoolRecord> pools = new ArrayList<>();
        private final List<RunSink.LedgerLine> lines = new ArrayList<>();
        private final List<RunSink.ResultLine> results = new ArrayList<>();
        private boolean committed;

        @Override
        public void pool(RunRepository.PoolRecord record) {
            pools.add(record);
        }

        @Override
        public void line(RunSink.LedgerLine line) {
            lines.add(line);
        }

        @Override
        public void result(RunSink.ResultLine line) {
            results.add(line);
        }

        @Override
        public void commit() {
            committed = true;
        }

        @Override
        public void close() {
        }
    }
}
