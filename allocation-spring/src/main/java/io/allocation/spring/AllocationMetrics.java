package io.allocation.spring;

import io.allocation.kernel.service.AllocationListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Run-level metrics, published as a listener rather than as a polling job.
 *
 * <h2>Why a listener and not a {@code MeterBinder} that queries the repository</h2>
 * The obvious implementation reads the run table every scrape and reports {@code count} per status. It is
 * wrong three ways: it costs a query per scrape per instance, it reports other instances' work as this
 * instance's, and it cannot report a duration at all — a table of statuses knows when a run finished, not
 * how long it took once it started. The listener sees {@code Started}, then {@code Published} or
 * {@code Failed}, on the run's own thread, for free.
 *
 * <h2>What is <em>not</em> here</h2>
 * <ul>
 *   <li><b>Per-pool meters.</b> A run with 100,000 pools would double the allocation rate to count
 *       itself. Pool progress is already a counter on the hot path ({@code Executor.Progress}) and is
 *       exposed through {@code status()}.</li>
 *   <li><b>Residual distribution.</b> "How many lots needed a tie-break" is a correctness question, and
 *       the answer is in the ledger ({@code got_residual} per line) and in {@code explain}. Counting it
 *       here would mean a comparison inside the per-pool settlement loop — measurement cost paid on the
 *       path the plan says is the expensive one — for a number an audit reads once.</li>
 *   <li><b>Percentiles and histograms.</b> Opt-in on the host side
 *       ({@code management.metrics.distribution.percentiles}); switched on here, every deployment pays
 *       for a clock and a ring buffer it may not export.</li>
 * </ul>
 *
 * <h2>Cardinality</h2>
 * The {@code rule} tag is the rule code — a deployment-time constant, one series per rule document. The
 * {@code code} tag is the error taxonomy, twelve values and never growing by host data. The run id is
 * never a tag: it is unique per run, which is the definition of a metric that ends a scrape interval in
 * an out-of-memory error.
 *
 * <h2>Threading</h2>
 * {@link #on} is called on the run's own thread. Every field here is either an immutable meter or a
 * concurrent collection; nothing is shared through a plain field.
 */
public final class AllocationMetrics implements AllocationListener {

    /** Runs accepted per rule; {@code idempotent_replay=true} means the key was already used. */
    static final String SUBMITTED = "allocation.run.submitted";

    /** Resubmissions caught by the idempotency key. A rising rate is a client retrying, not load. */
    static final String REPLAYED = "allocation.run.replayed";

    /** Runs that got a thread. */
    static final String STARTED = "allocation.run.started";

    /** Terminal runs, tagged {@code outcome} = {@code published | failed | cancelled}. */
    static final String FINISHED = "allocation.run.finished";

    /** Failed runs, tagged {@code code} = the {@code ALLOC_0xx} taxonomy. */
    static final String FAILURE = "allocation.run.failure";

    /** Submitted to started. This is the one that says "add workers or lower the queue". */
    static final String QUEUE_TIME = "allocation.run.queue.time";

    /** Started to terminal: the run's own cost, excluding time spent waiting. */
    static final String RUN_TIME = "allocation.run.duration";

    /** Pools per published run. */
    static final String POOLS = "allocation.run.pools";

    /** Result rows per published run. */
    static final String ROWS = "allocation.run.rows";

    /** Output amount per published run, in minor units — the same integer the ledger holds. */
    static final String AMOUNT = "allocation.run.amount";

    /** Runs submitted and not yet terminal, in this process. */
    static final String IN_FLIGHT = "allocation.run.in.flight";

    /** Tag value for a duration whose rule code is unknown; only reachable when events are replayed. */
    static final String UNKNOWN_RULE = "unknown";

    /** How a run's two timestamps are tracked between events. */
    private static final class Marks {

        final long submittedAt;
        final String rule;
        volatile long startedAt;

        Marks(long submittedAt, String rule) {
            this.submittedAt = submittedAt;
            this.rule = rule;
        }
    }

    private final MeterRegistry registry;
    private final Map<String, Marks> marks = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final DistributionSummary pools;
    private final DistributionSummary rows;
    private final DistributionSummary amount;

    public AllocationMetrics(MeterRegistry registry) {
        this.registry = registry;

        Gauge.builder(IN_FLIGHT, inFlight, AtomicInteger::get)
                .description("Runs submitted and not yet finished, in this process")
                .register(registry);

        // Registered with a zero value so a dashboard shows the series before the first run rather than
        // "no data" — a graph that only appears after a failure is a graph nobody trusts during one.
        Counter.builder(STARTED).description("Runs that began executing").register(registry);
        for (String outcome : new String[]{"published", "failed", "cancelled"}) {
            Counter.builder(FINISHED).tag("outcome", outcome)
                    .description("Runs that reached a terminal state").register(registry);
        }
        this.pools = DistributionSummary.builder(POOLS).baseUnit("pools")
                .description("Pools per published run").register(registry);
        this.rows = DistributionSummary.builder(ROWS).baseUnit("rows")
                .description("Result rows per published run").register(registry);
        this.amount = DistributionSummary.builder(AMOUNT).baseUnit("minor-units")
                .description("Output amount per published run, in the lexicon's integer minor units")
                .register(registry);
    }

    @Override
    public void on(Event event) {
        // Exhaustive over the sealed hierarchy and deliberately without a default branch: adding an event
        // variant must fail compilation here, rather than silently going uncounted.
        switch (event) {
            case Submitted submitted -> onSubmitted(submitted);
            case Started started -> onStarted(started);
            case Published published -> onPublished(published);
            case Failed failed -> onFailed(failed);
            case Cancelled cancelled -> onCancelled(cancelled);
        }
    }

    private void onSubmitted(Submitted event) {
        registry.counter(SUBMITTED, "rule", tag(event.ruleCode())).increment();
        if (event.idempotentReplay()) {
            // The run it refers to was counted when it was first submitted; charging a second queue time
            // to it would report a client's retry as capacity pressure.
            registry.counter(REPLAYED, "rule", tag(event.ruleCode())).increment();
            return;
        }
        marks.put(event.runId(), new Marks(event.atEpochMs(), tag(event.ruleCode())));
        inFlight.incrementAndGet();
    }

    private void onStarted(Started event) {
        registry.counter(STARTED).increment();
        Marks track = marks.get(event.runId());
        if (track == null) {
            // A replayed run: it was submitted in this process earlier and is already tracked, or the
            // submission was before this process started. Either way there is no queue time to report.
            return;
        }
        track.startedAt = event.atEpochMs();
        record(QUEUE_TIME, track.rule, event.atEpochMs() - track.submittedAt);
    }

    private void onPublished(Published event) {
        Marks track = take(event.runId());
        registry.counter(FINISHED, "outcome", "published").increment();
        recordRunTime(track, event.atEpochMs());
        pools.record(event.poolCount());
        rows.record(event.rowCount());
        amount.record(event.outputAmount());
    }

    private void onFailed(Failed event) {
        Marks track = take(event.runId());
        registry.counter(FINISHED, "outcome", "failed").increment();
        registry.counter(FAILURE, "code", event.code().code()).increment();
        recordRunTime(track, event.atEpochMs());
    }

    private void onCancelled(Cancelled event) {
        Marks track = take(event.runId());
        registry.counter(FINISHED, "outcome", "cancelled").increment();
        recordRunTime(track, event.atEpochMs());
    }

    /**
     * Removes the run's tracking record and reports it as no longer in flight.
     *
     * <p>Removal on <em>every</em> terminal event is what bounds the map: it can only hold runs that were
     * submitted and have not finished, which the engine bounds by queue capacity.
     */
    private Marks take(String runId) {
        inFlight.updateAndGet(current -> current > 0 ? current - 1 : 0);
        return marks.remove(runId);
    }

    private void recordRunTime(Marks track, long atEpochMs) {
        if (track == null || track.startedAt == 0L) {
            // Missing start means missing measurement. Recording a zero here would put a "fast run" into
            // the histogram that never happened and drag every average down with it.
            return;
        }
        record(RUN_TIME, track.rule, atEpochMs - track.startedAt);
    }

    private void record(String name, String rule, long millis) {
        if (millis < 0L) {
            // Only a wall clock stepping backwards produces this; a negative duration in a timer is worse
            // than a missing sample.
            return;
        }
        Timer.builder(name).tag("rule", rule).register(registry).record(Duration.ofMillis(millis));
    }

    /** Rule codes are host data: they never become a tag value that has to be escaped or normalised. */
    private static String tag(String code) {
        return code == null || code.isBlank() ? UNKNOWN_RULE : code;
    }
}
