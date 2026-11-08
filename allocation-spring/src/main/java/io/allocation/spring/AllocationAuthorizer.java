package io.allocation.spring;

/**
 * Decides whether the current caller may ask the engine to do something.
 *
 * <p>Exists because the controller is registered by auto-configuration: a host cannot put
 * {@code @PreAuthorize} on a method it does not own, and asking it to redeclare the controller in order to
 * add one annotation is not an extension point, it is a fork. So the check is a bean instead.
 *
 * <p>Called on the request thread, before the engine is touched, which means a denied call does not
 * create a run, does not read a rule and does not open a connection. It is also why this lives at the edge
 * and not in the kernel: the kernel has no notion of a principal, and inventing one it cannot verify would
 * be worse than admitting the boundary is here.
 *
 * <p><strong>In-process callers are not checked.</strong> A host that calls {@code AllocationTemplate}
 * from its own service code has already decided to trust that code; this interface governs the HTTP
 * surface. If a host needs the same rules for both, it calls {@code check} itself — one line, and it knows
 * who its caller is, which this interface does not.
 *
 * <p>Throw {@link io.allocation.kernel.AllocationException} with
 * {@link io.allocation.kernel.ErrorCode#FORBIDDEN} to refuse. Any other exception is a 500, because a
 * broken authorizer that silently permits is the failure mode worth being loud about.
 */
@FunctionalInterface
public interface AllocationAuthorizer {

    /** What the caller is trying to do. Coarse on purpose: one action per endpoint, not per field. */
    enum Action {

        /** Compute a few rows without persisting anything. */
        PREVIEW,

        /** Queue a full run. */
        SUBMIT,

        /** Read status, progress or a result page. */
        READ,

        /** Read one coordinate's decision chain. */
        EXPLAIN,

        /** Compare a published run against a reference file. */
        RECONCILE,

        /** Ask a run to stop. */
        CANCEL
    }

    /**
     * @param action what is being attempted
     * @param runId  the run it concerns, or {@code null} for {@link Action#PREVIEW} and {@link Action#SUBMIT},
     *               which act on a rule rather than on an existing run
     */
    void check(Action action, String runId);

    /**
     * The default: everything is allowed.
     *
     * <p>Not a placeholder. A component that cannot be evaluated without a security decision gets
     * evaluated in a sandbox and never adopted, and this product's own tests need a permissive default to
     * exercise the engine at all.
     */
    static AllocationAuthorizer permitAll() {
        return (action, runId) -> {
        };
    }
}
