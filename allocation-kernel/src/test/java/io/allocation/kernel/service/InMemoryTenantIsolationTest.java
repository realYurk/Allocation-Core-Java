package io.allocation.kernel.service;

/** The tenancy contract, against the in-memory repository. */
class InMemoryTenantIsolationTest extends TenantIsolationContract {

    private final RunRepository repository = new InMemoryRunRepository(provider());

    @Override
    RunRepository repository() {
        return repository;
    }
}
