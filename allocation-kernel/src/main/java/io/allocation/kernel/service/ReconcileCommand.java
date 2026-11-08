package io.allocation.kernel.service;

import io.allocation.kernel.reconcile.Reconciler;

import java.nio.file.Path;

/**
 * A reconciliation request against a published run.
 *
 * @param runId      run whose results are compared
 * @param reference  the independent answer, in the same coordinate shape
 * @param tolerance  absolute tolerance as a decimal literal, scaled by the rule precision
 * @param duplicates what to do when the reference repeats a coordinate
 */
public record ReconcileCommand(String runId, Path reference, String tolerance,
                               Reconciler.DuplicatePolicy duplicates) {

    public ReconcileCommand {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        if (reference == null) {
            throw new IllegalArgumentException("reference is required");
        }
        tolerance = tolerance == null || tolerance.isBlank() ? "0" : tolerance;
        duplicates = duplicates == null ? Reconciler.DuplicatePolicy.AGGREGATE : duplicates;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent construction. */
    public static final class Builder {
        private String runId;
        private Path reference;
        private String tolerance = "0";
        private Reconciler.DuplicatePolicy duplicates = Reconciler.DuplicatePolicy.AGGREGATE;

        public Builder runId(String value) {
            this.runId = value;
            return this;
        }

        public Builder reference(Path value) {
            this.reference = value;
            return this;
        }

        public Builder tolerance(String value) {
            this.tolerance = value;
            return this;
        }

        public Builder duplicatePolicy(Reconciler.DuplicatePolicy value) {
            this.duplicates = value;
            return this;
        }

        public ReconcileCommand build() {
            return new ReconcileCommand(runId, reference, tolerance, duplicates);
        }
    }
}
