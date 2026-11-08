package io.allocation.spring;

import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.service.RunRecovery;
import io.allocation.kernel.service.RunRepository;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Marks runs that a previous process left unfinished, once, while the context is still starting.
 *
 * <p>{@link SmartInitializingSingleton} rather than an {@code ApplicationRunner}: this must happen before
 * the first request is served, because "the status endpoint says RUNNING for a run nobody is executing"
 * is precisely the window it exists to close. An {@code ApplicationRunner} runs after the web server is
 * already accepting connections, which is a small window but the wrong one.
 *
 * <p>Nothing here decides whether the sweep is safe — {@link AllocationProperties#isRecoverInterruptedRuns()}
 * does, and it is off by default, because "started before this process booted" is only proof of
 * abandonment when one process owns the database.
 */
final class InterruptedRunSweep implements SmartInitializingSingleton {

    private static final System.Logger LOG = System.getLogger(InterruptedRunSweep.class.getName());

    private final RunRepository repository;
    private final long bootedAt;

    InterruptedRunSweep(RunRepository repository, long bootedAt) {
        this.repository = repository;
        this.bootedAt = bootedAt;
    }

    @Override
    public void afterSingletonsInstantiated() {
        int abandoned = RunRecovery.abandonInterrupted(repository, bootedAt);
        if (abandoned > 0) {
            LOG.log(System.Logger.Level.WARNING,
                    abandoned + " run(s) were abandoned by a previous process and marked FAILED ("
                            + ErrorCode.ABANDONED.code() + ")");
        }
    }
}
