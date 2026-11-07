package io.allocation.kernel.spec;

/** How a target dimension value is derived for each allocated row. */
public enum TargetMode {
    /** Inherit the pool's member. Requires {@link Grouping#AS_IS} for that dimension. */
    SOURCE,
    /** Always the constant {@code value}. */
    ELEMENT,
    /** Take the member from the driver row, restricted to an include list. */
    INCLUDE,
    /** Take the member from the driver row, restricted by include and exclude lists. */
    INCLUDE_EXCLUDE
}
