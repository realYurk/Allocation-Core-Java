package io.allocation.kernel.spec;

/** How a source dimension is folded when forming pools. */
public enum Grouping {
    /** Keep the incoming member. */
    AS_IS,
    /** Collapse to a single synthetic member. */
    TOTAL
}
