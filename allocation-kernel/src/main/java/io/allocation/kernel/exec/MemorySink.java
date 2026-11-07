package io.allocation.kernel.exec;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects every artefact in memory.
 *
 * <p>Used by tests and by the service layer when a caller asks for a preview or wants the ledger back in
 * the response. Retaining the full ledger for a large run is expensive — a million rows is about 80 MB —
 * so callers that only need results should use {@link #resultsOnly()} instead.
 */
public final class MemorySink implements RunSink {

    private final List<ResultLine> results = new ArrayList<>();
    private final List<LedgerLine> ledger = new ArrayList<>();
    private final List<MembershipLine> membership = new ArrayList<>();
    private final List<ReconciliationLine> reconciliation = new ArrayList<>();
    private final List<LineageLine> lineage = new ArrayList<>();
    private final List<Warning> warnings = new ArrayList<>();
    private final boolean retainEvidence;

    private MemorySink(boolean retainEvidence) {
        this.retainEvidence = retainEvidence;
    }

    /** Retains results and all evidence. */
    public static MemorySink full() {
        return new MemorySink(true);
    }

    /** Retains results and warnings only. */
    public static MemorySink resultsOnly() {
        return new MemorySink(false);
    }

    @Override
    public void result(ResultLine line) {
        results.add(line);
    }

    @Override
    public void ledger(LedgerLine line) {
        if (retainEvidence) {
            ledger.add(line);
        }
    }

    @Override
    public void membership(MembershipLine line) {
        if (retainEvidence) {
            membership.add(line);
        }
    }

    @Override
    public void reconciliation(ReconciliationLine line) {
        if (retainEvidence) {
            reconciliation.add(line);
        }
    }

    @Override
    public void lineage(LineageLine line) {
        if (retainEvidence) {
            lineage.add(line);
        }
    }

    @Override
    public void warning(Warning warning) {
        warnings.add(warning);
    }

    public List<ResultLine> results() {
        return results;
    }

    public List<LedgerLine> ledger() {
        return ledger;
    }

    public List<MembershipLine> membership() {
        return membership;
    }

    public List<ReconciliationLine> reconciliation() {
        return reconciliation;
    }

    public List<LineageLine> lineage() {
        return lineage;
    }

    public List<Warning> warnings() {
        return warnings;
    }
}
