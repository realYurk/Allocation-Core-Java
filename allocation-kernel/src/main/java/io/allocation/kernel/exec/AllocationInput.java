package io.allocation.kernel.exec;

import java.util.List;
import java.util.function.Consumer;

/**
 * Where amounts and drivers come from.
 *
 * <p>Deliberately tiny: five methods. The kernel does not know about files, JDBC or S3, so the same
 * engine serves a unit test, a nightly batch, and a Spring request. Implementations must return rows in
 * a stable order — file order, primary-key order, whatever — because row identifiers embed the ordinal
 * of rows that carry no explicit {@code row_id}.
 */
public interface AllocationInput {

    /** Reads the amount dataset in a stable order. */
    List<Row> readAmounts(String dataset);

    /**
     * Streams the amount dataset in the same order as {@link #readAmounts}, one row at a time.
     *
     * <p>This is the method the engine uses; {@code readAmounts} exists for callers that want the rows
     * as a value. The default materialises the list, so an implementation that cannot stream is still
     * correct — it just gives up the memory advantage. A file or a cursor should override it, because
     * the difference between "the whole table" and "one row" is the difference between a run that needs
     * a big heap and one that does not.
     */
    default void forEachAmount(String dataset, Consumer<Row> consumer) {
        for (Row row : readAmounts(dataset)) {
            consumer.accept(row);
        }
    }

    /** Reads the driver dataset for the given weight column, in a stable order. */
    List<DriverRow> readDrivers(String dataset, String weightField);

    /**
     * Streams the driver dataset, one row at a time, in the same order as {@link #readDrivers}.
     *
     * <p>The engine builds a compact column table from this stream instead of keeping the rows, so an
     * implementation that can read a file or a cursor row by row should override it: the difference is
     * whether a million-row driver file costs 30MB or 90MB of resident heap, and a driver table cannot
     * be spilled because allocation needs random access to it.
     */
    default void forEachDriver(String dataset, String weightField, Consumer<DriverRow> consumer) {
        for (DriverRow row : readDrivers(dataset, weightField)) {
            consumer.accept(row);
        }
    }

    /**
     * Label under which the dataset's hash is recorded in the run summary, for example a resolved path.
     * Defaults to the dataset name.
     */
    default String label(String dataset, String weightField) {
        return weightField == null ? dataset : dataset + "#" + weightField;
    }

    /**
     * Content hash of the dataset, or {@code null} when the source cannot provide one.
     * The engine records it in the run summary; it never verifies it.
     */
    String fingerprint(String dataset, String weightField);
}
