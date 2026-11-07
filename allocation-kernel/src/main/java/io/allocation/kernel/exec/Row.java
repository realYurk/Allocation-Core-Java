package io.allocation.kernel.exec;

import io.allocation.kernel.coord.Coord;

/**
 * One amount row moving through the DAG.
 *
 * <p>The {@code ids} array is captured defensively on construction and handed out without copying on
 * read. Rows are immutable after construction — {@link #withIds} and {@link #withAmount} return new
 * instances — so the array is never mutated in place. Copying it on every read would allocate once per
 * row per routing step, which for a million rows is millions of short-lived arrays for no safety gain.
 *
 * @param coord  full business coordinate
 * @param amount scaled amount
 * @param ids    lineage identifiers
 */
public record Row(Coord coord, long amount, String[] ids) {

    public Row {
        ids = ids.clone();
    }

    /** Lineage identifiers. Callers must treat the returned array as read-only. */
    @Override
    public String[] ids() {
        return ids;
    }

    public String firstId() {
        return ids.length == 0 ? "" : ids[0];
    }

    public Row withIds(String[] newIds) {
        return new Row(coord, amount, newIds);
    }

    public Row withAmount(long newAmount) {
        return new Row(coord, newAmount, ids);
    }
}
