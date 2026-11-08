package io.allocation.kernel.service;

/**
 * A full-run request.
 *
 * @param ruleCode       rule to execute
 * @param source         where the amounts come from
 * @param idempotencyKey optional; repeating a submission with the same key and the same rule returns
 *                       the original run instead of computing again
 * @param options        execution and retention settings
 */
public record RunCommand(String ruleCode, AmountSource source, String idempotencyKey, Options options) {

    public RunCommand {
        if (ruleCode == null || ruleCode.isBlank()) {
            throw new IllegalArgumentException("ruleCode is required");
        }
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        options = options == null ? Options.defaults() : options;
    }

    /**
     * Execution settings.
     *
     * @param workers            pool-level parallelism; output is identical for any value
     * @param retainResultLines  whether to keep terminal lines for {@code result}/{@code explain}
     * @param failOnMissingTarget whether a declared target without a driver weight blocks the run
     */
    public record Options(int workers, boolean retainResultLines, boolean failOnMissingTarget) {

        public Options {
            if (workers < 1) {
                throw new IllegalArgumentException("workers must be at least 1");
            }
        }

        public static Options defaults() {
            return new Options(1, true, false);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent construction. */
    public static final class Builder {
        private String ruleCode;
        private AmountSource source;
        private String idempotencyKey;
        private Options options = Options.defaults();

        public Builder ruleCode(String value) {
            this.ruleCode = value;
            return this;
        }

        public Builder source(AmountSource value) {
            this.source = value;
            return this;
        }

        public Builder idempotencyKey(String value) {
            this.idempotencyKey = value;
            return this;
        }

        public Builder options(Options value) {
            this.options = value;
            return this;
        }

        public RunCommand build() {
            return new RunCommand(ruleCode, source, idempotencyKey, options);
        }
    }
}
