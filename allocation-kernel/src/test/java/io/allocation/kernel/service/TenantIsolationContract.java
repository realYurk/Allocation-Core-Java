package io.allocation.kernel.service;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One set of tenancy assertions, run against both repositories.
 *
 * <p>A contract class rather than two test classes with the same body, because the point is not that
 * each repository has tests — it is that they answer identically. A host develops against the in-memory
 * repository and deploys against the JDBC one, so a filter that exists in only one of them passes every
 * test written before deployment and leaks in production.
 *
 * <p>The tenant is mutable within a test so one thread can act as two companies in turn, which is
 * exactly how the bug this contract exists to prevent would be triggered.
 */
abstract class TenantIsolationContract {

    private final AtomicReference<String> tenant = new AtomicReference<>("acme");

    /** A repository whose tenancy follows {@link #actAs}. */
    abstract RunRepository repository();

    /** The tenant source to hand to the repository under test; the same object {@link #actAs} drives. */
    protected final TenantProvider provider() {
        return tenant::get;
    }

    /** Act as this tenant; {@code null} means the system context. */
    final void actAs(String next) {
        tenant.set(next);
    }

    private RunRepository.RunRecord pending(String runId, String idempotencyKey) {
        return new RunRepository.RunRecord(runId, "cost-allocation", "a".repeat(64), null, idempotencyKey,
                "b".repeat(64), 2, RunRepository.Status.PENDING, 0L, 0L, 0, 0L, null, null,
                1_700_000_000_000L, null);
    }

    @Test
    void aRunIsInvisibleToAnotherTenant() {
        actAs("acme");
        repository().insert(pending("run-acme", "batch-2026-01"));

        actAs("globex");

        assertTrue(repository().find("run-acme").isEmpty(),
                "another company's run must not be findable by id");
        assertTrue(repository().findByIdempotencyKey("batch-2026-01").isEmpty(),
                "and must not be reachable by an idempotency key the other company happens to reuse");
    }

    @Test
    void aRunWithoutAKeyIsInvisibleToo() {
        actAs("acme");
        repository().insert(pending("run-nokey", null));

        actAs("globex");

        assertTrue(repository().find("run-nokey").isEmpty());
    }

    @Test
    void aSystemContextSeesEveryTenant() {
        actAs("acme");
        repository().insert(pending("run-acme", null));
        actAs("globex");
        repository().insert(pending("run-globex", null));

        actAs(null);

        assertTrue(repository().find("run-acme").isPresent(),
                "a batch job has no tenant and must not be blind to the runs it is asked to look at");
        assertTrue(repository().find("run-globex").isPresent());
    }

    @Test
    void anEmptyTenantIsASystemContext() {
        // An interceptor that resolves nothing usually returns "" rather than null; treating the two
        // differently would make the empty value a tenant that sees only its own runs.
        actAs("");
        repository().insert(pending("run-blank", "batch-2026-01"));

        assertTrue(repository().find("run-blank").isPresent());

        actAs("acme");
        assertTrue(repository().find("run-blank").isEmpty());
    }

    @Test
    void theSameKeyInTwoTenantsIsTwoRuns() {
        actAs("globex");
        repository().insert(pending("run-globex", "batch-2026-01"));
        actAs("acme");
        repository().insert(pending("run-acme", "batch-2026-01"));

        assertEquals("run-acme", repository().findByIdempotencyKey("batch-2026-01").orElseThrow().runId());
        actAs("globex");
        assertEquals("run-globex", repository().findByIdempotencyKey("batch-2026-01").orElseThrow().runId());

        actAs(null);
        assertTrue(repository().find("run-acme").isPresent());
        assertTrue(repository().find("run-globex").isPresent(),
                "two companies numbering their monthly batch the same way must not cost one of them its run");
    }

    @Test
    void aSystemContextDoesNotShareATenantsIdempotencyKey() {
        // The system context is the empty tenant, not "all tenants". Otherwise a nightly job using the key
        // "batch-2026-01" would collide with whichever company used it first, or be answered with that
        // company's run — the failure the composite key exists to prevent, from the other direction.
        actAs("acme");
        repository().insert(pending("run-acme", "batch-2026-01"));

        actAs(null);

        assertTrue(repository().findByIdempotencyKey("batch-2026-01").isEmpty());
        repository().insert(pending("run-system", "batch-2026-01"));
        assertEquals("run-system", repository().findByIdempotencyKey("batch-2026-01").orElseThrow().runId());
    }

    @Test
    void theSameKeyInOneTenantStillCollides() {
        repository().insert(pending("run-1", "batch-2026-01"));

        AllocationException e = assertThrows(AllocationException.class,
                () -> repository().insert(pending("run-2", "batch-2026-01")));

        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, e.code());
    }

    @Test
    void aFinishedRunKeepsItsTenant() {
        RunRepository.RunRecord run = pending("run-acme", null);
        repository().insert(run);
        repository().update(run.published(1_000L, 1_000L, 1, 2L, 1_700_000_000_100L));

        actAs("globex");

        assertTrue(repository().find("run-acme").isEmpty(),
                "a status update must not become a way to move a run between tenants");
    }
}
