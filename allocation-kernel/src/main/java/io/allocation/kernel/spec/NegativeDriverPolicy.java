package io.allocation.kernel.spec;

/** What to do with a negative driver weight. */
public enum NegativeDriverPolicy {
    /** Reject the run. The default, and the only safe default. */
    REJECT,
    /** Permit negative weights; requires a documented business reason on the rule. */
    ALLOW_SIGNED
}
