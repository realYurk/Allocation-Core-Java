package io.allocation.kernel.service;

import java.nio.file.Path;
import java.util.List;

/**
 * Where the amounts come from.
 *
 * <p>Two shapes are enough for the service layer: a directory the rule's own dataset paths resolve
 * against, and rows a caller has already decoded. The second is what makes a "preview" identical to a
 * batch run — same executor, same arithmetic, different way of arriving at the rows — which is the
 * whole reason the input is an interface rather than a file reader.
 */
public sealed interface AmountSource permits AmountSource.Csv, AmountSource.InMemory {

    /**
     * Datasets read from {@code baseDirectory}, using the relative paths declared in the rule itself.
     *
     * @param baseDirectory directory the rule's dataset paths are relative to
     */
    record Csv(Path baseDirectory) implements AmountSource {
        public Csv {
            if (baseDirectory == null) {
                throw new IllegalArgumentException("baseDirectory is required");
            }
        }
    }

    /** Rows supplied in memory, already decoded by the caller. */
    record InMemory(List<AmountRow> rows) implements AmountSource {
        public InMemory {
            rows = List.copyOf(rows);
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("in-memory preview needs at least one row");
            }
        }
    }
}
