package io.allocation.kernel.service;

import io.allocation.kernel.exec.AllocationInput;

import java.nio.file.Path;
import java.util.List;

/**
 * Where the amounts and driver weights come from.
 *
 * <p>Three shapes: a directory the rule's own dataset paths resolve against, rows a caller has already
 * decoded, and an {@link AllocationInput} the host implemented itself. The third is not a convenience —
 * it is the only way a host whose data lives in a database, an object store or a mapper cursor can run a
 * full allocation at all. Until it existed, the "override {@code forEachAmount}" advice in
 * {@code docs/API.md} §6 was a documented promise with no door into the service layer.
 *
 * <p>The second shape is what makes a "preview" identical to a batch run — same executor, same
 * arithmetic, different way of arriving at the rows — which is the whole reason the input is an interface
 * rather than a file reader.
 */
public sealed interface AmountSource permits AmountSource.Csv, AmountSource.InMemory, AmountSource.Custom {

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

    /**
     * An input the host implemented: a JDBC cursor, an object-store stream, a mapper iterator.
     *
     * <p>Everything the engine needs from a source is already on {@link AllocationInput}: stream the
     * amounts, stream the drivers, and say what the data was. A host that overrides {@code forEachAmount}
     * and {@code forEachDriver} keeps memory proportional to one row rather than to the table — the
     * difference between a run that needs a big heap and one that does not.
     *
     * <p><b>A fingerprint is not optional here when an idempotency key is used.</b> The key is a promise
     * that "the same key means the same data", and the engine keeps that promise by hashing what it read.
     * An input returning {@code null} from {@link AllocationInput#fingerprint} cannot tell two different
     * tables apart, so the promise would turn a retry after a data change into the previous run's answer —
     * a wrong number that looks like a cache hit. That submission is refused with {@code ALLOC_013}
     * instead of answered. Without a key, a null fingerprint is fine and is recorded as {@code absent} in
     * the run summary.
     */
    record Custom(AllocationInput input) implements AmountSource {
        public Custom {
            if (input == null) {
                throw new IllegalArgumentException("a custom source needs an AllocationInput");
            }
        }
    }
}
