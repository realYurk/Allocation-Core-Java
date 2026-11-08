package io.allocation.kernel.service;

import io.allocation.kernel.coord.Coord;
import io.allocation.kernel.exec.RunSink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The paging adapter that lets a service-side reconciliation read a large run.
 *
 * <p>The failure this guards against is not an exception: an off-by-one in the offset math silently
 * compares a subset and reports MATCH. So the assertions are about the whole sequence — count, first,
 * last and order — across a page boundary that is deliberately not a round multiple.
 */
class RepositoryAmountRowsTest {

    private static RunRepository.RunRecord running(String runId) {
        return new RunRepository.RunRecord(runId, "rule", "sha", null, "request-" + runId, 2,
                RunRepository.Status.PUBLISHED, 0, 0, 0, 0, null, null, 0L, 0L);
    }

    private static InMemoryRunRepository repositoryWith(int rows) {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        repository.insert(running("run-1"));
        RunRepository.EvidenceWriter writer = repository.openEvidence("run-1");
        for (int i = 0; i < rows; i++) {
            writer.result(new RunSink.ResultLine("st1", "row-" + i,
                    new Coord(new String[]{"P" + (i % 7), "A" + i}), i));
        }
        writer.commit();
        writer.close();
        return repository;
    }

    @Test
    void streamsEveryRowAcrossPagesInOrder() {
        int rows = RepositoryAmountRows.PAGE_SIZE * 2 + 17;
        InMemoryRunRepository repository = repositoryWith(rows);
        List<RunSink.ResultLine> collected = new ArrayList<>();

        new RepositoryAmountRows(repository, "run-1").stream((coordinate, amount) -> {
            Coord coord = Coord.parse(coordinate);
            collected.add(new RunSink.ResultLine("st1", "", coord, amount));
        });

        assertEquals(rows, collected.size(), "paging must not drop or duplicate rows");
        assertEquals("A0", collected.get(0).coord().member(1));
        assertEquals("A" + (rows - 1), collected.get(collected.size() - 1).coord().member(1));
        for (int i = 0; i < rows; i++) {
            assertEquals(i, collected.get(i).amount(), "row " + i + " arrived out of order");
        }
    }

    @Test
    void anEmptyRunStreamsNothing() {
        InMemoryRunRepository repository = repositoryWith(0);
        List<Long> amounts = new ArrayList<>();
        new RepositoryAmountRows(repository, "run-1").stream((coordinate, amount) -> amounts.add(amount));
        assertTrue(amounts.isEmpty());
    }

    @Test
    void aRunOfExactlyOnePageStillTerminates() {
        // The loop stops on a short page; a run of exactly PAGE_SIZE rows would otherwise ask for one
        // more page forever if the boundary were handled wrongly.
        InMemoryRunRepository repository = repositoryWith(RepositoryAmountRows.PAGE_SIZE);
        List<Long> amounts = new ArrayList<>();
        new RepositoryAmountRows(repository, "run-1").stream((coordinate, amount) -> amounts.add(amount));
        assertEquals(RepositoryAmountRows.PAGE_SIZE, amounts.size());
    }

    @Test
    void labelsTheRunRatherThanAFile() {
        InMemoryRunRepository repository = repositoryWith(1);
        assertEquals("run run-1", new RepositoryAmountRows(repository, "run-1").label());
    }
}
