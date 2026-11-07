package io.allocation.kernel.exec;

import io.allocation.kernel.io.CsvWriteException;
import io.allocation.kernel.io.CsvWriter;
import io.allocation.kernel.money.Decimal;
import io.allocation.kernel.spec.AllocationSpec;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes every artefact as a delimited file in a directory.
 *
 * <p>All files carry a UTF-8 byte-order mark and {@code \n} line endings, and amounts are formatted with
 * the rule's precision. Those three details are what make two runs diffable with {@code diff} rather
 * than with a bespoke comparator, and what make the output identical across operating systems.
 *
 * <p>Instances are single-threaded by contract: the executor calls the sink from its coordinator thread
 * only, so no locking is needed and ordering is guaranteed.
 */
public final class CsvDirectorySink implements RunSink, AutoCloseable {

    private final CsvWriter results;
    private final CsvWriter ledger;
    private final CsvWriter membership;
    private final CsvWriter reconciliation;
    private final CsvWriter lineage;
    private final int precision;

    private CsvDirectorySink(CsvWriter results, CsvWriter ledger, CsvWriter membership,
                             CsvWriter reconciliation, CsvWriter lineage, int precision) {
        this.results = results;
        this.ledger = ledger;
        this.membership = membership;
        this.reconciliation = reconciliation;
        this.lineage = lineage;
        this.precision = precision;
    }

    /** Creates the five evidence files inside {@code directory}. */
    public static CsvDirectorySink create(Path directory, AllocationSpec spec) {
        try {
            Files.createDirectories(directory);
            List<String> resultHeader = new ArrayList<>();
            resultHeader.add("terminal_stage");
            resultHeader.add("row_id");
            resultHeader.addAll(spec.dimensions());
            resultHeader.add("amount");
            return new CsvDirectorySink(
                    new CsvWriter(open(directory, "results.csv"), true).withHeader(resultHeader),
                    new CsvWriter(open(directory, "ledger.csv"), true).withHeader(List.of(
                            "stage", "rule", "pool", "target", "target_row_id", "pool_amount",
                            "share_amount", "driver_weight", "driver_denominator", "allocated_amount")),
                    new CsvWriter(open(directory, "source_to_pool.csv"), true).withHeader(List.of(
                            "stage", "pool", "upstream_row_id", "input_amount")),
                    new CsvWriter(open(directory, "reconciliation.csv"), true).withHeader(List.of(
                            "stage", "pool", "input_amount", "output_amount", "delta", "status")),
                    new CsvWriter(open(directory, "lineage_links.csv"), true).withHeader(List.of(
                            "stage", "event", "parent_row_id", "child_row_id", "parent_amount")),
                    spec.precision());
        } catch (IOException e) {
            throw new CsvWriteException("cannot create evidence directory " + directory, e);
        }
    }

    private static OutputStream open(Path directory, String name) throws IOException {
        return Files.newOutputStream(directory.resolve(name));
    }

    @Override
    public void result(ResultLine line) {
        List<String> fields = new ArrayList<>(line.coord().size() + 3);
        fields.add(line.stage());
        fields.add(line.rowId());
        for (int i = 0; i < line.coord().size(); i++) {
            fields.add(line.coord().memberOrEmpty(i));
        }
        fields.add(Decimal.format(line.amount(), precision));
        results.write(fields);
    }

    @Override
    public void ledger(LedgerLine line) {
        ledger.write(List.of(line.stage(), line.rule(), line.pool(), line.target(), line.targetRowId(),
                Decimal.format(line.poolAmount(), precision),
                Decimal.format(line.shareAmount(), precision),
                Decimal.format(line.driverWeight(), 6),
                Decimal.format(line.driverDenominator(), 6),
                Decimal.format(line.allocatedAmount(), precision)));
    }

    @Override
    public void membership(MembershipLine line) {
        membership.write(List.of(line.stage(), line.pool(), line.upstreamRowId(),
                Decimal.format(line.inputAmount(), precision)));
    }

    @Override
    public void reconciliation(ReconciliationLine line) {
        reconciliation.write(List.of(line.stage(), line.pool(),
                Decimal.format(line.inputAmount(), precision),
                Decimal.format(line.outputAmount(), precision),
                Decimal.format(line.delta(), precision),
                line.status()));
    }

    @Override
    public void lineage(LineageLine line) {
        lineage.write(List.of(line.stage(), line.event(), line.parentRowId(), line.childRowId(),
                Decimal.format(line.parentAmount(), precision)));
    }

    @Override
    public void close() {
        results.close();
        ledger.close();
        membership.close();
        reconciliation.close();
        lineage.close();
    }

    /**
     * Closes every file without raising, used on the failure path.
     *
     * <p>This does not make the artefacts "safe" on its own; the caller deletes the staging directory,
     * which is what actually guarantees that a failed run leaves nothing behind that could be mistaken
     * for a result.
     */
    public void abort() {
        try {
            results.close();
        } catch (RuntimeException ignored) {
            // best effort
        }
        try {
            ledger.close();
        } catch (RuntimeException ignored) {
            // best effort
        }
        try {
            membership.close();
        } catch (RuntimeException ignored) {
            // best effort
        }
        try {
            reconciliation.close();
        } catch (RuntimeException ignored) {
            // best effort
        }
        try {
            lineage.close();
        } catch (RuntimeException ignored) {
            // best effort
        }
    }
}
