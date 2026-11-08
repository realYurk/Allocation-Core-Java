package io.allocation.kernel.reconcile;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A one-pass source of coordinates and amounts.
 *
 * <p>Deliberately a callback rather than a collection: the whole reason this exists is that a
 * ten-million-row side must never be materialised. A caller that already holds the rows in memory can
 * still implement it by iterating them, which is what keeps one comparison implementation serving the
 * small case, the large case and the tests.
 *
 * <p>Amounts are scaled by the rule precision; the coordinate is the engine's canonical JSON key, so
 * {@code ["2024-01","P1","A2"]} means one thing everywhere.
 */
@FunctionalInterface
public interface AmountRows {

    /** Receives rows in file order. Implementations must not call {@code stream} twice. */
    void stream(RowConsumer consumer) throws IOException;

    /** Name shown in diagnostics, usually the file path. */
    default String label() {
        return "rows";
    }

    /**
     * SHA-256 of the bytes this source read, or {@code null} when the source is not a file.
     *
     * <p>Only meaningful after {@link #stream} has returned: a report that names the exact bytes it
     * compared can be audited later, and one that names only a path cannot.
     */
    default String digest() {
        return null;
    }

    /** One row. */
    @FunctionalInterface
    interface RowConsumer {
        void accept(String coordinate, long amount);
    }

    /** Adapts rows already in memory. */
    static AmountRows of(Map<String, Long> amounts) {
        return new AmountRows() {
            @Override
            public void stream(RowConsumer consumer) {
                amounts.forEach(consumer::accept);
            }

            @Override
            public String label() {
                return "memory";
            }
        };
    }

    /** Adapts a list of coordinate/amount pairs, preserving order. */
    static AmountRows of(List<Map.Entry<String, Long>> rows) {
        return of(rows, "memory");
    }

    /** Adapts a list of coordinate/amount pairs, preserving order. */
    static AmountRows of(List<Map.Entry<String, Long>> rows, String label) {
        return new AmountRows() {
            @Override
            public void stream(RowConsumer consumer) {
                for (Map.Entry<String, Long> row : rows) {
                    consumer.accept(row.getKey(), row.getValue());
                }
            }

            @Override
            public String label() {
                return label;
            }
        };
    }

    /** Adapts an iterator, so a paged source can be compared without being loaded whole. */
    static AmountRows of(Iterator<Map.Entry<String, Long>> rows, String label) {
        return new AmountRows() {
            @Override
            public void stream(RowConsumer consumer) {
                while (rows.hasNext()) {
                    Map.Entry<String, Long> row = rows.next();
                    consumer.accept(row.getKey(), row.getValue());
                }
            }

            @Override
            public String label() {
                return label;
            }
        };
    }
}
