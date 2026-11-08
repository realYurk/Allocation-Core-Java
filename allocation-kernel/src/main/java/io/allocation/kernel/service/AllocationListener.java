package io.allocation.kernel.service;

import io.allocation.kernel.ErrorCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Observes the lifecycle of runs.
 *
 * <p>Exists because a host almost always needs the same four things — an audit row when a run is
 * submitted, a metric when it publishes, an alert when it fails, a log line when somebody cancels — and
 * the alternatives are all worse: subclassing {@link DefaultAllocationTemplate} (which locks the host to
 * one implementation), polling the repository (which turns a five-second event into a scheduled job), or
 * patching the kernel (which the host cannot do).
 *
 * <p>One method taking a sealed event rather than five methods named after the states. Later versions
 * will need more events than these five, and a method is a name a host has already compiled against. The
 * event hierarchy grows by adding a variant; a host that switches should keep a {@code default} branch,
 * and this is stated here rather than discovered later.
 *
 * <p>Implementations are called <em>on the run's own thread</em>, in the middle of its lifecycle. A
 * listener must therefore be quick and must not throw: the engine catches whatever escapes and logs it,
 * so one bad listener cannot turn a successful run into a failed one. Work that needs to be slow belongs
 * on a queue owned by the listener.
 *
 * <p>Per-pool observation is <em>not</em> an event. A run with 100,000 pools would produce 100,000 of
 * them, and the executor already reports at that granularity through {@link
 * io.allocation.kernel.exec.Executor.Progress}, which is a counter read on the hot path rather than an
 * object allocated per pool.
 */
@FunctionalInterface
public interface AllocationListener {

    /** A listener that ignores everything; what a host that does not care gets. */
    static AllocationListener noop() {
        return Noop.INSTANCE;
    }

    /**
     * The shared do-nothing listener.
     *
     * <p>A named constant rather than a fresh lambda per call, because {@link DefaultAllocationTemplate}
     * skips the notify path by identity: every event it would have sent is one virtual call plus, on the
     * failure path, a stack trace being constructed for nothing.
     */
    final class Noop implements AllocationListener {

        static final Noop INSTANCE = new Noop();

        private Noop() {
        }

        @Override
        public void on(Event event) {
        }
    }

    /**
     * One listener that forwards to several, in the order given.
     *
     * <p>The engine takes one listener and a host routinely has two: its own audit hook, plus whatever
     * the starter installs (metrics, tracing, a test probe). Before this method existed the host had to
     * write the fan-out itself, and the obvious implementation — a lambda that calls each in turn — has a
     * defect that only shows up in production: the first listener that throws stops the ones after it.
     * Here each call is isolated, so a broken audit hook cannot also silence the metrics.
     *
     * <p>{@link Noop} instances are dropped and a single remaining listener is returned as-is, both so
     * that {@link DefaultAllocationTemplate} keeps its identity fast path. An empty list yields {@link
     * #noop()}.
     */
    static AllocationListener allOf(List<AllocationListener> listeners) {
        List<AllocationListener> targets = new ArrayList<>(listeners.size());
        for (AllocationListener listener : listeners) {
            if (listener != null && listener != Noop.INSTANCE) {
                targets.add(listener);
            }
        }
        if (targets.isEmpty()) {
            return Noop.INSTANCE;
        }
        return targets.size() == 1 ? targets.get(0) : new Composite(targets);
    }

    /**
     * The fan-out returned by {@link #allOf(List)}.
     *
     * <p>Package-visible only through {@code allOf}; a host that wants ordering guarantees gets them from
     * the order it passed in.
     */
    final class Composite implements AllocationListener {

        private static final System.Logger LOG = System.getLogger(Composite.class.getName());

        private final List<AllocationListener> targets;

        Composite(List<AllocationListener> targets) {
            this.targets = List.copyOf(targets);
        }

        @Override
        public void on(Event event) {
            for (int i = 0; i < targets.size(); i++) {
                try {
                    targets.get(i).on(event);
                } catch (RuntimeException | Error failure) {
                    // Swallowed on purpose: the engine's own guard would end the run's listener calls for
                    // this event, and the other listeners have not been told yet.
                    LOG.log(System.Logger.Level.WARNING, "allocation listener " + (i + 1) + " of "
                            + targets.size() + " failed on " + event.getClass().getSimpleName()
                            + " for run " + event.runId(), failure);
                }
            }
        }
    }

    void on(Event event);

    /** Something that happened to a run. Every variant carries the run id and the wall clock. */
    sealed interface Event permits Submitted, Started, Published, Failed, Cancelled {

        String runId();

        long atEpochMs();
    }

    /**
     * The run was accepted and is queued.
     *
     * @param idempotentReplay true when the key had already been used, so nothing was queued and the
     *                         event refers to the run that existed before
     */
    record Submitted(String runId, String ruleCode, boolean idempotentReplay, long atEpochMs)
            implements Event {
    }

    /** Execution began: the record moved to {@code RUNNING} and evidence is being written. */
    record Started(String runId, long atEpochMs) implements Event {
    }

    /**
     * The evidence is committed and the result is readable.
     *
     * <p>The amounts are the run's own totals as they were written to the record, not a re-derivation, so
     * a listener that audits conservation is auditing exactly what the caller will see.
     */
    record Published(String runId, long inputAmount, long outputAmount, int poolCount, long rowCount,
                     long atEpochMs) implements Event {
    }

    /** The run ended without an answer. {@code code} is why. */
    record Failed(String runId, ErrorCode code, String message, long atEpochMs) implements Event {
    }

    /** Somebody stopped the run. Not a failure, and deliberately a separate variant. */
    record Cancelled(String runId, long atEpochMs) implements Event {
    }
}
