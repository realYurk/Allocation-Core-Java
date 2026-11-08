package io.allocation.spring;

import io.allocation.kernel.service.AllocationTemplate;
import io.allocation.kernel.service.Explain;
import io.allocation.kernel.service.Preview;
import io.allocation.kernel.service.PreviewCommand;
import io.allocation.kernel.service.ReconcileCommand;
import io.allocation.kernel.service.RunCommand;
import io.allocation.kernel.service.RunResult;
import io.allocation.kernel.service.RunView;

import java.time.Duration;
import java.util.Map;

/**
 * An {@link AllocationTemplate} that asks {@link AllocationAuthorizer} before delegating.
 *
 * <p>A decorator rather than a change to the engine, because the engine's own contract says so: "the
 * interface exists so a host can substitute a decorator — authorisation, metrics, a dry-run switch —
 * without subclassing the implementation". This is that sentence implemented once, so every host does not
 * write it again (and get {@code cancel} wrong).
 *
 * <p>Every method checks first, including the read paths. A result page is data; a caller who may not
 * {@link AllocationAuthorizer.Action#READ READ} a run must not learn its id exists by receiving a
 * differently-shaped 409.
 */
public final class AuthorizingAllocationTemplate implements AllocationTemplate, AutoCloseable {

    private final AllocationTemplate delegate;
    private final AllocationAuthorizer authorizer;

    public AuthorizingAllocationTemplate(AllocationTemplate delegate, AllocationAuthorizer authorizer) {
        this.delegate = delegate;
        this.authorizer = authorizer == null ? AllocationAuthorizer.permitAll() : authorizer;
    }

    @Override
    public Preview preview(PreviewCommand command) {
        authorizer.check(AllocationAuthorizer.Action.PREVIEW, null);
        return delegate.preview(command);
    }

    @Override
    public SubmitResult submit(RunCommand command) {
        authorizer.check(AllocationAuthorizer.Action.SUBMIT, null);
        return delegate.submit(command);
    }

    @Override
    public RunView status(String runId) {
        authorizer.check(AllocationAuthorizer.Action.READ, runId);
        return delegate.status(runId);
    }

    @Override
    public RunResult await(String runId, Duration timeout) {
        authorizer.check(AllocationAuthorizer.Action.READ, runId);
        return delegate.await(runId, timeout);
    }

    @Override
    public RunResult result(String runId, int page, int size) {
        authorizer.check(AllocationAuthorizer.Action.READ, runId);
        return delegate.result(runId, page, size);
    }

    @Override
    public Explain explain(String runId, Map<String, String> coord) {
        authorizer.check(AllocationAuthorizer.Action.EXPLAIN, runId);
        return delegate.explain(runId, coord);
    }

    @Override
    public ReconcileOutcome reconcile(ReconcileCommand command) {
        authorizer.check(AllocationAuthorizer.Action.RECONCILE, command.runId());
        return delegate.reconcile(command);
    }

    @Override
    public boolean cancel(String runId) {
        authorizer.check(AllocationAuthorizer.Action.CANCEL, runId);
        return delegate.cancel(runId);
    }

    /**
     * Releases the engine this wraps.
     *
     * <p>Present because Spring infers a destroy method from the bean's actual class: a wrapper without one
     * would silently leave the delegate's thread executor running until the JVM exits, which looks fine in
     * tests and shows up as a shutdown that never completes in production.
     */
    @Override
    public void close() {
        if (delegate instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("closing the allocation engine failed", e);
            }
        }
    }
}
