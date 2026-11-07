package io.allocation.kernel.exec;

import io.allocation.kernel.coord.Coord;

/**
 * Receives every artefact a run produces.
 *
 * <p>Defining the sink as callbacks rather than a set of files is what lets the same engine serve a CSV
 * batch, a database-backed service, and a test that wants to assert on the ledger directly. The
 * directory implementation and the in-memory implementation therefore exercise exactly the same code
 * path — there is no "production mode" that is only ever tested in production.
 *
 * <p>Ordering guarantee: all callbacks arrive in a single thread, in deterministic order (stage order,
 * then pool key ascending). Implementations need no synchronisation.
 */
public interface RunSink {

    /** One terminal output row. */
    record ResultLine(String stage, String rowId, Coord coord, long amount) {
    }

    /** One allocation decision: how much of a pool went to one target, and why. */
    record LedgerLine(String stage, String rule, String pool, String target, String targetRowId,
                      long poolAmount, long shareAmount, long driverWeight, long driverDenominator,
                      long allocatedAmount) {
    }

    /** One source row entering a pool, before allocation. */
    record MembershipLine(String stage, String pool, String upstreamRowId, long inputAmount) {
    }

    /** Pool-level conservation evidence. */
    record ReconciliationLine(String stage, String pool, long inputAmount, long outputAmount,
                              long delta, String status) {
    }

    /** One lineage edge: which upstream row became which downstream row. */
    record LineageLine(String stage, String event, String parentRowId, String childRowId, long parentAmount) {
    }

    /**
     * A non-fatal observation.
     *
     * @param code machine-readable identifier, for example {@code W_TARGET_MISSING_DRIVER}
     */
    record Warning(String code, String message, String stageId, String allocationId) {
    }

    void result(ResultLine line);

    void ledger(LedgerLine line);

    void membership(MembershipLine line);

    void reconciliation(ReconciliationLine line);

    void lineage(LineageLine line);

    /** Non-fatal observations. The default ignores them. */
    default void warning(Warning warning) {
    }
}
