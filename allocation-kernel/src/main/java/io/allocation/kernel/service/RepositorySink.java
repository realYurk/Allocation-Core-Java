package io.allocation.kernel.service;

import io.allocation.kernel.exec.RunSink;
import io.allocation.kernel.money.Overflow;

/**
 * Forwards a run's evidence straight to the repository, as it is produced.
 *
 * <p>The alternative — collecting everything in memory and saving it at the end — is simpler to write
 * and wrong at scale: a million rows is a few hundred megabytes of objects that exist only to be copied
 * into a database, and the collector spends the run making that worse.
 *
 * <p>Two details are worth knowing:
 *
 * <ul>
 *   <li><b>Pool target counts are derived, not looked up.</b> The engine emits a pool's ledger lines and
 *       then exactly one reconciliation line for that pool, so the number of ledger lines since the last
 *       reconciliation is that pool's target count. This is a dependency on the executor's documented
 *       emission order, which is why the order is documented.</li>
 *   <li><b>Totals are summed here, not re-read.</b> The run's input and output totals come from the same
 *       reconciliation lines the file sink writes, so the database and the CSV archive can never disagree
 *       about what a run moved.</li>
 * </ul>
 */
final class RepositorySink implements RunSink {

    private final RunRepository.EvidenceWriter writer;
    private final boolean retainDetails;

    private long resultRows;
    private long poolRows;
    private long inputAmount;
    private long outputAmount;
    private int ledgerSinceReconciliation;

    /**
     * @param retainDetails whether to keep per-target results and the ledger; pool-level evidence is always
     *                      written, because that is the balance proof
     */
    RepositorySink(RunRepository.EvidenceWriter writer, boolean retainDetails) {
        this.writer = writer;
        this.retainDetails = retainDetails;
    }

    @Override
    public void result(ResultLine line) {
        resultRows++;
        if (retainDetails) {
            writer.result(line);
        }
    }

    @Override
    public void ledger(LedgerLine line) {
        ledgerSinceReconciliation++;
        if (retainDetails) {
            writer.line(line);
        }
    }

    @Override
    public void membership(MembershipLine line) {
        // The source-to-pool map is an artefact for a person reading files; it is not queried by the
        // service and not part of the audit spine, so it does not cost a row here.
    }

    @Override
    public void reconciliation(ReconciliationLine line) {
        poolRows++;
        inputAmount = Overflow.addExact(inputAmount, line.inputAmount());
        outputAmount = Overflow.addExact(outputAmount, line.outputAmount());
        writer.pool(new RunRepository.PoolRecord(line.stage(), line.pool(), line.inputAmount(),
                line.outputAmount(), ledgerSinceReconciliation));
        ledgerSinceReconciliation = 0;
    }

    @Override
    public void lineage(LineageLine line) {
        // Same reasoning as membership: useful in the file archive, not persisted per row.
    }

    /** Number of terminal result rows produced. */
    long resultRows() {
        return resultRows;
    }

    /** Number of pools that balanced, which is the run's pool count. */
    long poolRows() {
        return poolRows;
    }

    long inputAmount() {
        return inputAmount;
    }

    long outputAmount() {
        return outputAmount;
    }
}
