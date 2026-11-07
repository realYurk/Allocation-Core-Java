package io.allocation.kernel.spec;

/** What a stage does with its inbox. */
public enum StageOperation {
    /** Split each pool across candidate targets. The default. */
    ALLOCATE,
    /** Coalesce rows sharing a full coordinate; amounts are summed. */
    MERGE,
    /** Pass rows through untouched. */
    PASS
}
