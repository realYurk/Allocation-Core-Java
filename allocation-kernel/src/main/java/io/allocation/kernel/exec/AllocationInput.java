package io.allocation.kernel.exec;

import java.util.List;

/**
 * Where amounts and drivers come from.
 *
 * <p>Deliberately tiny: four methods. The kernel does not know about files, JDBC or S3, so the same
 * engine serves a unit test, a nightly batch, and a Spring request. Implementations must return rows in
 * a stable order — file order, primary-key order, whatever — because row identifiers embed the ordinal
 * of rows that carry no explicit {@code row_id}.
 */
public interface AllocationInput {

    /** Reads the amount dataset in a stable order. */
    List<Row> readAmounts(String dataset);

    /** Reads the driver dataset for the given weight column, in a stable order. */
    List<DriverRow> readDrivers(String dataset, String weightField);

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
