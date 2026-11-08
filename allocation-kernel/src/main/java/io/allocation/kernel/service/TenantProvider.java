package io.allocation.kernel.service;

/**
 * Whose data the caller may see.
 *
 * <p>Optional. A host that is not multi-tenant declares nothing and the engine behaves exactly as it
 * did before tenants existed. A host that is declares one bean, and {@code allocation_run} gains a
 * filter on every read.
 *
 * <h2>Why this is a port and not a column the caller fills in</h2>
 * A tenant filter is the one kind of access control that cannot be enforced politely. A missing
 * {@code WHERE} clause is not a rejected request, it is another company's numbers in a response, and
 * nobody notices until an auditor does. So the filter is applied in one place — where a run is resolved
 * from its id — and no read path can reach {@code allocation_pool}, {@code allocation_line} or
 * {@code allocation_result} without passing through it.
 *
 * <h2>When it is called</h2>
 * Once per run, on the submitting thread, when the run row is written; and on every path that turns a
 * run id from the caller into a run. It is <em>not</em> consulted by the worker threads that execute a
 * run: they never read a run row, and a {@link ThreadLocal}-based identity would not follow them there
 * anyway. A host that submits from its own thread pool must therefore carry the tenant into that thread
 * itself — the same rule any {@code ThreadLocal}-based framework imposes on any asynchronous call.
 *
 * <h2>What null means</h2>
 * {@code null} means "this thread is acting as the system, not as a tenant", and reads are not
 * filtered. That is the correct answer for a nightly batch job and for the startup sweep that abandons
 * interrupted runs, and it is what the surrounding framework does: a tenant interceptor only adds its
 * condition when a tenant is in context. A host that needs the opposite — every read must name a
 * tenant — implements that in {@code AllocationAuthorizer}, which sees the request rather than the
 * storage.
 */
@FunctionalInterface
public interface TenantProvider {

    /** The tenant of the current thread, or {@code null} when there is no tenant context. */
    String currentTenant();

    /**
     * Single-tenant deployments: no context, so no filter.
     *
     * <p>Shared instance rather than a new lambda per call, so the repository can take a null-check-free
     * fast path on identity if it ever needs one.
     */
    static TenantProvider none() {
        return None.INSTANCE;
    }

    /** The shared "no tenants here" provider. */
    enum None implements TenantProvider {
        INSTANCE;

        @Override
        public String currentTenant() {
            return null;
        }
    }
}
