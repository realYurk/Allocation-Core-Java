package io.allocation.kernel.service;

import java.util.List;

/**
 * A preview request.
 *
 * @param ruleCode rule to execute
 * @param rows     amount rows, already scaled
 * @param explain  whether to include the per-coordinate decision trace
 */
public record PreviewCommand(String ruleCode, List<AmountRow> rows, boolean explain) {

    /** Maximum rows a synchronous preview accepts, so a request cannot monopolise a thread. */
    public static final int MAX_ROWS = 100_000;

    public PreviewCommand {
        if (ruleCode == null || ruleCode.isBlank()) {
            throw new IllegalArgumentException("ruleCode is required");
        }
        rows = List.copyOf(rows);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent construction. */
    public static final class Builder {
        private String ruleCode;
        private List<AmountRow> rows = List.of();
        private boolean explain;

        public Builder ruleCode(String value) {
            this.ruleCode = value;
            return this;
        }

        public Builder rows(List<AmountRow> value) {
            this.rows = value;
            return this;
        }

        public Builder explain(boolean value) {
            this.explain = value;
            return this;
        }

        public PreviewCommand build() {
            return new PreviewCommand(ruleCode, rows, explain);
        }
    }
}
