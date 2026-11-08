package io.allocation.kernel.service;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.coord.Coord;
import io.allocation.kernel.exec.AllocationInput;
import io.allocation.kernel.exec.CsvInput;
import io.allocation.kernel.exec.DriverRow;
import io.allocation.kernel.exec.Executor;
import io.allocation.kernel.exec.MemorySink;
import io.allocation.kernel.exec.Row;
import io.allocation.kernel.exec.RunSink;
import io.allocation.kernel.exec.RunSummary;
import io.allocation.kernel.io.Digests;
import io.allocation.kernel.money.Apportion;
import io.allocation.kernel.money.Decimal;
import io.allocation.kernel.plan.Plan;
import io.allocation.kernel.plan.Planner;
import io.allocation.kernel.reconcile.AmountRows;
import io.allocation.kernel.reconcile.CsvAmountRows;
import io.allocation.kernel.reconcile.ShardedReconciler;
import io.allocation.kernel.spec.AllocationRule;
import io.allocation.kernel.spec.AllocationSpec;
import io.allocation.kernel.spec.SpecCodec;
import io.allocation.kernel.spec.StageSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The default service implementation: one rule, one input, one evidence chain.
 *
 * <p>Preview and full run share the same executor and therefore the same arithmetic — a preview that
 * agreed with a batch run because it was written twice would be an expensive way to learn nothing.
 *
 * <p>Concurrency is deliberate about two things. Runs execute on virtual threads, because each one is a
 * long CPU-and-IO task and pinning them to a small pool makes a queue look like a hang. Admission is
 * bounded by a semaphore, so a burst of submissions is refused with {@code ALLOC_007} instead of
 * growing an unbounded queue until the process dies.
 *
 * <p>A run's evidence is written as it is produced, not collected and saved at the end: the sink forwards
 * each row to a {@link RunRepository.EvidenceWriter}, so the cost of a run is bounded by the rows the
 * executor holds for a stage, not by the size of its output. Progress is reported from the executor's own
 * loop between pool batches, which is why {@link #status(String)} can answer "620 of 1000 pools" while
 * the run is still going instead of answering zero until it ends.
 */
public final class DefaultAllocationTemplate implements AllocationTemplate, AutoCloseable {

    /** JDK logger, so the kernel keeps its zero-dependency promise and a host can still see failures. */
    private static final System.Logger LOG = System.getLogger(DefaultAllocationTemplate.class.getName());

    private final RunRepository repository;
    private final RuleResolver rules;
    private final Path workingDirectory;
    private final Semaphore admission;
    private final ExecutorService workers;
    private final AllocationListener listener;

    /**
     * Runs this process owns, and the flag that asks each one to stop.
     *
     * <p>The map is the definition of "owned": a run present here is executing in this process and can be
     * stopped, a run absent from it cannot. Entries are removed when the run reaches a terminal state, so
     * the map is bounded by the queue capacity rather than by history.
     */
    private final Map<String, AtomicBoolean> cancellations = new ConcurrentHashMap<>();

    /**
     * Live progress of the runs this process is executing.
     *
     * <p>Separate from the repository because it is not evidence: it is a number that is true now and
     * meaningless later. Persisting it would add a write per pool batch to the hot path in exchange for a
     * figure that a crashed process would leave permanently wrong.
     */
    private final Map<String, AtomicReference<RunView.Progress>> liveProgress = new ConcurrentHashMap<>();

    public DefaultAllocationTemplate(RunRepository repository, RuleResolver rules, Path workingDirectory) {
        this(repository, rules, workingDirectory, 16);
    }

    public DefaultAllocationTemplate(RunRepository repository, RuleResolver rules, Path workingDirectory,
                                     int queueCapacity) {
        this(repository, rules, workingDirectory, queueCapacity, AllocationListener.noop());
    }

    /**
     * @param listener notified of every run's lifecycle; called on the run's own thread, never allowed to
     *                 break the run
     */
    public DefaultAllocationTemplate(RunRepository repository, RuleResolver rules, Path workingDirectory,
                                     int queueCapacity, AllocationListener listener) {
        this.repository = repository;
        this.rules = rules;
        this.workingDirectory = workingDirectory.toAbsolutePath();
        this.admission = new Semaphore(queueCapacity);
        this.workers = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("allocation-run-", 0).factory());
        this.listener = listener == null ? AllocationListener.noop() : listener;
    }

    @Override
    public Preview preview(PreviewCommand command) {
        if (command.rows().size() > PreviewCommand.MAX_ROWS) {
            throw new AllocationException(ErrorCode.REQUEST_TOO_LARGE,
                    "preview accepts at most " + PreviewCommand.MAX_ROWS + " rows, got " + command.rows().size());
        }
        RuleResolver.RuleDocument rule = rules.require(command.ruleCode());
        AllocationSpec spec = SpecCodec.parse(rule.json());
        Plan plan = Planner.compile(spec);
        String dataset = singleAmountDataset(spec);

        MemorySink sink = MemorySink.full();
        Executor.execute(plan, rule.sha256(), previewInput(rule, spec, dataset, command.rows()), sink,
                new Executor.Options(1, false));

        Map<String, Decision> decisions = command.explain()
                ? decisions(spec, sink.ledger()) : Map.of();
        List<Preview.AllocatedLine> lines = new ArrayList<>(sink.results().size());
        for (RunSink.ResultLine line : sink.results()) {
            Decision decision = decisions.get(traceKey(line));
            lines.add(new Preview.AllocatedLine(coord(line, spec.dimensions()), new Money(line.amount(), spec.precision()),
                    decision == null ? null : new Preview.ShareTrace(new Money(decision.line().poolAmount(), spec.precision()),
                            decision.line().driverWeight(), decision.line().driverDenominator(),
                            decision.rank(), decision.gotResidual())));
        }
        long input = 0L;
        long output = 0L;
        for (RunSink.ReconciliationLine line : sink.reconciliation()) {
            input += line.inputAmount();
            output += line.outputAmount();
        }
        return new Preview(lines, new Preview.Conservation(new Money(input, spec.precision()),
                new Money(output, spec.precision()), input == output), sink.warnings());
    }

    @Override
    public SubmitResult submit(RunCommand command) {
        RuleResolver.RuleDocument rule = rules.require(command.ruleCode());
        AllocationSpec spec = SpecCodec.parse(rule.json());
        Planner.compile(spec);

        // Built once: the digest needs the datasets' contents, and the run needs the same input. Doing
        // this per submission also means a file that changes between the digest and the run is not a
        // possibility, because there is only the one read of each dataset.
        AllocationInput input = inputFor(rule, spec, command.source());
        String requestDigest = requestDigest(rule, spec, command.source(), input);
        String key = command.idempotencyKey();
        if (key != null) {
            requireFingerprints(spec, command.source(), input, key);
            Optional<RunRepository.RunRecord> existing = repository.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                RunRepository.RunRecord record = existing.get();
                if (!record.requestSha256().equals(requestDigest)) {
                    throw new AllocationException(ErrorCode.IDEMPOTENCY_CONFLICT,
                            "idempotency key " + key + " was used for a different rule or input");
                }
                // Reported, but marked as a replay: a host counting submissions per rule wants to see
                // retries, and a host counting work done wants to skip them. One event answers both.
                notify(new AllocationListener.Submitted(record.runId(), rule.code(), true,
                        System.currentTimeMillis()));
                return new SubmitResult(record.runId(), record.status(), true);
            }
        }
        if (!admission.tryAcquire()) {
            throw new AllocationException(ErrorCode.REQUEST_TOO_LARGE, "allocation queue is full");
        }
        RunRepository.RunRecord pending = new RunRepository.RunRecord(RunIds.next(), rule.code(), rule.sha256(),
                key, requestDigest, spec.precision(), RunRepository.Status.PENDING, 0L, 0L, 0, 0L, null, null,
                System.currentTimeMillis(), null);
        try {
            repository.insert(pending);
        } catch (RuntimeException e) {
            admission.release();
            // A concurrent submission with the same key won the insert: answer with the winner.
            if (key != null) {
                Optional<RunRepository.RunRecord> winner = repository.findByIdempotencyKey(key);
                if (winner.isPresent() && winner.get().requestSha256().equals(requestDigest)) {
                    notify(new AllocationListener.Submitted(winner.get().runId(), rule.code(), true,
                            System.currentTimeMillis()));
                    return new SubmitResult(winner.get().runId(), winner.get().status(), true);
                }
            }
            throw e;
        }
        // Registered before dispatch, so a cancel that arrives while the run is still queued is not lost,
        // and so the first poll of a queued run already has something to report.
        cancellations.put(pending.runId(), new AtomicBoolean());
        AtomicReference<RunView.Progress> progress = new AtomicReference<>(new RunView.Progress(null, 0, 0, 0L));
        liveProgress.put(pending.runId(), progress);
        notify(new AllocationListener.Submitted(pending.runId(), rule.code(), false,
                System.currentTimeMillis()));
        workers.execute(() -> {
            try {
                executeRun(pending, rule, spec, command, input, progress);
            } finally {
                cancellations.remove(pending.runId());
                liveProgress.remove(pending.runId());
                admission.release();
            }
        });
        return new SubmitResult(pending.runId(), RunRepository.Status.PENDING, false);
    }

    @Override
    public boolean cancel(String runId) {
        AtomicBoolean flag = cancellations.get(runId);
        if (flag == null) {
            return false;
        }
        return !flag.getAndSet(true);
    }

    @Override
    public RunView status(String runId) {
        RunRepository.RunRecord record = requireRun(runId);
        long elapsed = (record.finishedAtEpochMs() == null ? System.currentTimeMillis()
                : record.finishedAtEpochMs()) - record.startedAtEpochMs();
        return new RunView(runId, record.status(), progressOf(record), record.startedAtEpochMs(),
                Math.max(0L, elapsed));
    }

    /**
     * Progress for a run: live while it is executing, recorded once it is over.
     *
     * <p>The two sources answer different questions. A finished run's figures are its record — pools it
     * wrote, rows it emitted — and they survive a restart because they were committed with the run. A
     * running run has no record yet, so without the live figure every poll of a ten-minute job would
     * report zero, which is indistinguishable from a hang.
     *
     * <p>The terminal branch does not read the pool evidence, and that is a deliberate half of the
     * answer: the count was written by the same counter that wrote the rows, so re-deriving it means a
     * table scan per poll. A UI refreshing a finished 100,000-pool run would pull 100,000 rows each time
     * to display a number the run row already holds. The cost of not reading is that an abandoned run
     * (process killed between committing evidence and recording the counts) reports what its record says
     * — zero — rather than what its orphaned evidence would suggest; a run in that state is already
     * marked {@code ALLOC_012} and is not something a progress bar can help with.
     */
    private RunView.Progress progressOf(RunRepository.RunRecord record) {
        if (record.status().terminal()) {
            // No stage id: these are the run's own totals, summed over every stage. Both counts come from
            // the same place, so a terminal run is 100% of itself by construction and can never report
            // more completed pools than it has.
            return new RunView.Progress(null, record.poolCount(), record.poolCount(), record.rowCount());
        }
        AtomicReference<RunView.Progress> live = liveProgress.get(record.runId());
        if (live != null) {
            return live.get();
        }
        // Executing in another process (or queued and never picked up): the only evidence available is
        // what that process has already committed.
        return new RunView.Progress(null, repository.pools(record.runId()).size(), record.poolCount(),
                record.rowCount());
    }

    @Override
    public RunResult await(String runId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            RunRepository.RunRecord record = requireRun(runId);
            if (record.status().terminal()) {
                return result(runId, 1, 1);
            }
            if (System.nanoTime() > deadline) {
                throw new AllocationException(ErrorCode.TIMEOUT, "run " + runId + " still " + record.status());
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AllocationException(ErrorCode.INTERNAL, "interrupted while waiting for " + runId, e);
            }
        }
    }

    @Override
    public RunResult result(String runId, int page, int size) {
        RunRepository.RunRecord record = requirePublished(runId);
        if (page < 1 || size < 1) {
            throw new AllocationException(ErrorCode.RULE_INVALID, "page and size must be at least 1");
        }
        AllocationSpec spec = specOf(record);
        List<RunSink.ResultLine> rows = repository.results(runId, (page - 1) * size, size);
        Map<String, Decision> decisions = decisionsFor(spec, runId, rows);
        List<Preview.AllocatedLine> lines = new ArrayList<>();
        for (RunSink.ResultLine line : rows) {
            Decision decision = decisions.get(traceKey(line));
            lines.add(new Preview.AllocatedLine(coord(line, spec.dimensions()), new Money(line.amount(), spec.precision()),
                    decision == null ? null : new Preview.ShareTrace(new Money(decision.line().poolAmount(), spec.precision()),
                            decision.line().driverWeight(), decision.line().driverDenominator(),
                            decision.rank(), decision.gotResidual())));
        }
        return new RunResult(runId, record.status(), lines, new Preview.Conservation(
                new Money(record.inputAmount(), spec.precision()),
                new Money(record.outputAmount(), spec.precision()),
                record.inputAmount() == record.outputAmount()),
                page, size, repository.resultCount(runId));
    }

    @Override
    public Explain explain(String runId, Map<String, String> coord) {
        RunRepository.RunRecord record = requirePublished(runId);
        AllocationSpec spec = specOf(record);
        String[] members = new String[spec.dimensions().size()];
        for (int i = 0; i < members.length; i++) {
            String name = spec.dimensions().get(i);
            members[i] = coord.get(name);
            if (members[i] == null) {
                throw new AllocationException(ErrorCode.RULE_INVALID, "coord is missing dimension " + name);
            }
        }
        String key = new Coord(members).key();
        List<Explain.Step> steps = new ArrayList<>();
        for (Decision decision : decisionsForTargets(spec, runId, Set.of(key)).values()) {
            RunSink.LedgerLine line = decision.line();
            if (!line.target().equals(key)) {
                continue;
            }
            steps.add(new Explain.Step(line.stage(), line.rule(), line.pool(),
                    new Money(line.poolAmount(), spec.precision()), new Money(line.shareAmount(), spec.precision()),
                    line.driverWeight(), line.driverDenominator(), decision.rank(), decision.gotResidual(),
                    new Money(line.allocatedAmount(), spec.precision())));
        }
        return new Explain(runId, coord, record.ruleSha256(), steps);
    }

    @Override
    public ReconcileOutcome reconcile(ReconcileCommand command) {
        RunRepository.RunRecord record = requirePublished(command.runId());
        AllocationSpec spec = specOf(record);
        long tolerance = Decimal.parseScaled(command.tolerance(), spec.precision());
        ShardedReconciler.Options options = ShardedReconciler.Options.of(spec.dimensions(), spec.precision())
                .withTolerance(tolerance)
                .withDuplicates(command.duplicates());

        // A report is named by what was compared: the run plus the digest of the reference bytes. Both
        // sides are immutable, so the same pair can only ever produce the same file — publishing it twice
        // would overwrite evidence with a copy of itself. Naming by digest also keeps two comparisons of
        // the same run against two different references from colliding.
        Path target;
        try {
            target = workingDirectory.resolve("reconcile").resolve(command.runId())
                    .resolve(Digests.sha256Hex(command.reference()).substring(0, 16));
        } catch (IOException e) {
            throw new AllocationException(ErrorCode.INTERNAL,
                    "cannot read reference " + command.reference(), e);
        }
        try {
            Files.createDirectories(target.getParent());
            AmountRows actual = new RepositoryAmountRows(repository, command.runId());
            AmountRows reference = new CsvAmountRows(command.reference(), spec.dimensions(), spec.precision());
            ShardedReconciler.Summary summary = Files.exists(target)
                    ? ShardedReconciler.compare(actual, reference, options)
                    : ShardedReconciler.compare(actual, reference, options, target);
            return new ReconcileOutcome(summary, target.resolve(ShardedReconciler.DIFFERENCES_FILE).toString());
        } catch (IOException e) {
            throw new AllocationException(ErrorCode.INTERNAL,
                    "cannot reconcile run " + command.runId() + " against " + command.reference(), e);
        }
    }

    @Override
    public void close() {
        workers.shutdown();
    }

    // ---------------------------------------------------------------- execution

    private void executeRun(RunRepository.RunRecord pending, RuleResolver.RuleDocument rule,
                            AllocationSpec spec, RunCommand command, AllocationInput data,
                            AtomicReference<RunView.Progress> progress) {
        repository.update(pending.withStatus(RunRepository.Status.RUNNING));
        notify(new AllocationListener.Started(pending.runId(), System.currentTimeMillis()));
        AtomicBoolean cancelled = cancellations.get(pending.runId());
        RunRepository.EvidenceWriter writer = repository.openEvidence(pending.runId());
        try {
            Plan plan = Planner.compile(spec);
            RepositorySink sink = new RepositorySink(writer, command.options().retainResultLines());
            RunSummary summary = Executor.execute(plan, rule.sha256(), data, sink,
                    new Executor.Options(command.options().workers(),
                            command.options().failOnMissingTarget(),
                            command.options().maxRowsInMemory()),
                    cancelled == null ? Executor.Cancellation.none() : cancelled::get,
                    (stageId, totalPools, completedPools, rows) -> progress.set(
                            new RunView.Progress(stageId, completedPools, totalPools, rows)));

            // Evidence first, status second. A poller that sees PUBLISHED can therefore always read a
            // complete result set, which is the only ordering that makes the status meaningful.
            writer.commit();
            // The executor's totals, not the sink's: on a multi-stage rule the sum of the stage inputs is a
            // multiple of what the run actually moved, and the record must answer "how much did this run
            // allocate" once.
            RunRepository.RunRecord published = pending.published(summary.inputAmount(), summary.outputAmount(),
                    (int) sink.poolRows(), sink.resultRows(), System.currentTimeMillis());
            repository.update(published);
            notify(new AllocationListener.Published(pending.runId(), summary.inputAmount(), summary.outputAmount(),
                    (int) sink.poolRows(), sink.resultRows(), published.finishedAtEpochMs()));
        } catch (AllocationException e) {
            if (e.code() == ErrorCode.CANCELLED) {
                // No commit, so the writer's close discards everything this run produced: a cancelled run
                // must not leave rows that a later reader could mistake for an answer.
                repository.update(pending.cancelled(System.currentTimeMillis()));
                notify(new AllocationListener.Cancelled(pending.runId(), System.currentTimeMillis()));
                return;
            }
            recordFailure(pending, e.code().code(), truncate(e.getMessage()), e, e.code());
        } catch (RuntimeException e) {
            recordFailure(pending, ErrorCode.INTERNAL.code(), truncate(String.valueOf(e)), e, ErrorCode.INTERNAL);
        } finally {
            // No-op after a successful commit; otherwise it discards whatever the failed run wrote.
            writer.close();
        }
    }

    /**
     * Tells the listener about an event, and survives whatever the listener does with it.
     *
     * <p>A listener is host code running inside the engine's own thread. If it throws, the run it was
     * observing must not be the thing that dies — so the exception is logged and dropped. Swallowing an
     * exception is normally the wrong call; here the alternative is a metrics exporter taking down
     * allocations, which is a strictly worse failure.
     */
    private void notify(AllocationListener.Event event) {
        if (listener == AllocationListener.noop()) {
            return;
        }
        try {
            listener.on(event);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "allocation listener failed on " + event.getClass().getSimpleName() + " for run "
                            + event.runId() + ": " + e, e);
        }
    }

    /**
     * Marks the run failed, without letting a broken repository hide the reason the run failed.
     *
     * <p>If the database is unavailable at this point the update throws, and the original cause would be
     * lost to an uncaught exception on a virtual thread that nothing is watching. Recording that the
     * record could not be recorded is the only thing left to do, and it goes to the JDK logger because
     * the kernel carries no logging dependency.
     */
    private void recordFailure(RunRepository.RunRecord pending, String code, String message, RuntimeException cause,
                               ErrorCode taxonomy) {
        try {
            repository.update(pending.failed(code, message, System.currentTimeMillis()));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR,
                    "run " + pending.runId() + " failed with " + code + " and could not be marked failed: " + e,
                    cause);
        }
        notify(new AllocationListener.Failed(pending.runId(), taxonomy, message, System.currentTimeMillis()));
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    // ---------------------------------------------------------------- inputs

    private static String singleAmountDataset(AllocationSpec spec) {
        if (spec.amountDatasets().size() != 1) {
            throw new AllocationException(ErrorCode.RULE_INVALID,
                    "preview requires a rule with exactly one amount dataset, found " + spec.amountDatasets().size());
        }
        return spec.amountDatasets().keySet().iterator().next();
    }

    private static AllocationInput inputFor(RuleResolver.RuleDocument rule, AllocationSpec spec, AmountSource source) {
        if (source instanceof AmountSource.Csv csv) {
            return CsvInput.of(csv.baseDirectory(), spec);
        }
        if (source instanceof AmountSource.Custom custom) {
            return custom.input();
        }
        AmountSource.InMemory memory = (AmountSource.InMemory) source;
        return previewInput(rule, spec, singleAmountDataset(spec), memory.rows());
    }

    /**
     * Refuses an idempotency key on an input that cannot say what it read.
     *
     * <p>Only custom inputs are checked, and only when a key is present: the two built-in sources always
     * have something to hash (a file, or the caller's rows), so this cannot change their behaviour. The
     * alternative — recording {@code absent} and answering the retry anyway — is the defect D-016 was
     * written about, one layer out: a submission whose identity was never established, reported as a
     * replay of a run that read different data.
     *
     * <p>A name is collected per dataset rather than throwing on the first miss, because a host debugging
     * this wants the whole list in one response.
     */
    private static void requireFingerprints(AllocationSpec spec, AmountSource source, AllocationInput input,
                                            String key) {
        if (!(source instanceof AmountSource.Custom)) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String dataset : spec.amountDatasets().keySet()) {
            if (input.fingerprint(dataset, null) == null) {
                missing.add(dataset);
            }
        }
        for (String[] reference : driverReferences(spec)) {
            if (input.fingerprint(reference[0], reference[1]) == null) {
                missing.add(reference[0] + "." + reference[1]);
            }
        }
        if (!missing.isEmpty()) {
            throw new AllocationException(ErrorCode.IDEMPOTENCY_UNVERIFIABLE,
                    "idempotency key " + key + " needs a fingerprint for every dataset this rule reads; "
                            + "the custom input returned none for " + String.join(", ", missing),
                    null, null, null, null);
        }
    }

    /** Every {@code (dataset, weightField)} pair the rule's allocations read, deduplicated and sorted. */
    private static List<String[]> driverReferences(AllocationSpec spec) {
        TreeSet<String> references = new TreeSet<>();
        for (StageSpec stage : spec.stages()) {
            for (AllocationRule allocation : stage.allocations()) {
                references.add(allocation.driverDataset() + '\u0000' + allocation.weightField());
            }
        }
        List<String[]> out = new ArrayList<>(references.size());
        for (String reference : references) {
            int split = reference.indexOf('\u0000');
            out.add(new String[]{reference.substring(0, split), reference.substring(split + 1)});
        }
        return out;
    }

    private static AllocationInput previewInput(RuleResolver.RuleDocument rule, AllocationSpec spec,
                                                String dataset, List<AmountRow> rows) {
        List<Row> built = new ArrayList<>(rows.size());
        int ordinal = 0;
        for (AmountRow row : rows) {
            String[] members = new String[spec.dimensions().size()];
            for (int i = 0; i < members.length; i++) {
                String name = spec.dimensions().get(i);
                String value = row.coord().get(name);
                if (value == null) {
                    throw new AllocationException(ErrorCode.RULE_INVALID,
                            "row " + ordinal + " is missing dimension " + name);
                }
                members[i] = value;
            }
            built.add(new Row(new Coord(members), row.amount().unitsAt(spec.precision()),
                    new String[]{Integer.toString(++ordinal)}));
        }
        return new PreviewInput(CsvInput.of(rule.baseDirectory(), spec), dataset, built);
    }

    /**
     * Replaces one dataset with rows held in memory and delegates everything else to the file input.
     *
     * <p>Drivers still come from the rule's own datasets: a preview asks "what would this rule do with
     * these amounts", not "what would it do with made-up weights".
     */
    private record PreviewInput(AllocationInput delegate, String dataset, List<Row> rows) implements AllocationInput {

        @Override
        public List<Row> readAmounts(String name) {
            return dataset.equals(name) ? rows : delegate.readAmounts(name);
        }

        @Override
        public List<DriverRow> readDrivers(String name, String weightField) {
            return delegate.readDrivers(name, weightField);
        }

        @Override
        public String fingerprint(String name, String weightField) {
            return delegate.fingerprint(name, weightField);
        }
    }

    // ---------------------------------------------------------------- explanation

    /**
     * One recorded allocation decision plus its place in the settlement order.
     *
     * <p>The rank is recomputed from the recorded weights rather than stored, because the stored ledger
     * is a frozen file format shared with the reference implementation. Recomputing it with
     * {@link Apportion#settlementOrder} keeps a single copy of the tie-break policy.
     */
    private record Decision(RunSink.LedgerLine line, int rank, boolean gotResidual) {
    }

    /**
     * The decisions behind one page of results, read pool by pool instead of run by run.
     *
     * <p>This used to read the whole ledger for every page. For the run sizes this engine is built for
     * that is not slow, it is fatal: a million-row run would materialise a million ledger objects —
     * hundreds of megabytes — to explain ten lines, on every request.
     *
     * <p>Two reads, both bounded. The first fetches the page's own lines, which says which pools are
     * involved; the second fetches those pools whole, because a target's remainder rank is a property of
     * its pool and cannot be recomputed from one line. The bound is therefore "page size x targets per
     * pool", never "size of the run".
     */
    private Map<String, Decision> decisionsFor(AllocationSpec spec, String runId, List<RunSink.ResultLine> page) {
        if (page.isEmpty()) {
            return Map.of();
        }
        Set<String> targets = new LinkedHashSet<>();
        for (RunSink.ResultLine line : page) {
            targets.add(line.coord().key());
        }
        return decisionsForTargets(spec, runId, targets);
    }

    private Map<String, Decision> decisionsForTargets(AllocationSpec spec, String runId, Set<String> targets) {
        List<RunSink.LedgerLine> own = repository.linesForTargets(runId, targets);
        if (own.isEmpty()) {
            return Map.of();
        }
        Set<PoolRef> pools = new LinkedHashSet<>();
        for (RunSink.LedgerLine line : own) {
            pools.add(new PoolRef(line.stage(), line.rule(), line.pool()));
        }
        List<RunSink.LedgerLine> whole = new ArrayList<>();
        for (PoolRef pool : pools) {
            whole.addAll(repository.linesForPool(runId, pool.stage(), pool.rule(), pool.pool()));
        }
        return decisions(spec, whole);
    }

    /** One pool's ledger group: the unit that has to be read together to rank its targets. */
    private record PoolRef(String stage, String rule, String pool) {
    }

    private static String traceKey(RunSink.ResultLine line) {
        return line.stage() + '\u0000' + line.coord().key() + '\u0000' + line.rowId();
    }

    private static Map<String, Decision> decisions(AllocationSpec spec, List<RunSink.LedgerLine> ledger) {
        Map<String, Boolean> signed = new HashMap<>();
        for (StageSpec stage : spec.stages()) {
            for (var rule : stage.allocations()) {
                signed.put(stage.id() + '\u0000' + rule.id(), rule.allowsSigned());
            }
        }
        Map<String, List<RunSink.LedgerLine>> groups = new LinkedHashMap<>();
        for (RunSink.LedgerLine line : ledger) {
            groups.computeIfAbsent(line.stage() + '\u0000' + line.rule() + '\u0000' + line.pool(),
                    key -> new ArrayList<>()).add(line);
        }
        Map<String, Decision> out = new HashMap<>();
        for (List<RunSink.LedgerLine> group : groups.values()) {
            int n = group.size();
            long[] weights = new long[n];
            String[] identities = new String[n];
            for (int i = 0; i < n; i++) {
                weights[i] = group.get(i).driverWeight();
                identities[i] = group.get(i).target();
            }
            boolean allowSigned = signed.getOrDefault(group.get(0).stage() + '\u0000' + group.get(0).rule(), false);
            Apportion.Settlement settlement = Apportion.settlement(
                    group.get(0).shareAmount(), weights, identities, allowSigned);
            int[] rank = settlement.rank();
            long magnitude = settlement.residual() < 0 ? -settlement.residual() : settlement.residual();
            for (int i = 0; i < n; i++) {
                RunSink.LedgerLine line = group.get(i);
                out.put(line.stage() + '\u0000' + line.target() + '\u0000' + line.targetRowId(),
                        new Decision(line, rank[i], rank[i] < magnitude));
            }
        }
        return out;
    }

    private static Map<String, String> coord(RunSink.ResultLine line, List<String> dimensions) {
        Map<String, String> coord = new LinkedHashMap<>();
        for (int i = 0; i < dimensions.size(); i++) {
            coord.put(dimensions.get(i), line.coord().memberOrEmpty(i));
        }
        return coord;
    }

    private AllocationSpec specOf(RunRepository.RunRecord record) {
        return SpecCodec.parse(rules.require(record.ruleCode()).json());
    }

    /**
     * The one place a caller-supplied run id becomes a run.
     *
     * <p>Every read path goes through here — status, await, result, explain, reconcile — which is what
     * makes it the right place for the tenant filter to bite: the repository decides whether a run is
     * visible, this method decides that an invisible run is an error, and nothing below it can be reached
     * without both. It is also why {@code ALLOC_014} exists instead of reusing a validation code: "no such
     * run" is a 404, and a caller told "invalid rule" would spend an afternoon on its rule.
     */
    private RunRepository.RunRecord requireRun(String runId) {
        return repository.find(runId).orElseThrow(() -> new AllocationException(ErrorCode.RUN_NOT_FOUND,
                "unknown run " + runId));
    }

    private RunRepository.RunRecord requirePublished(String runId) {
        RunRepository.RunRecord record = requireRun(runId);
        if (record.status() != RunRepository.Status.PUBLISHED) {
            throw new AllocationException(ErrorCode.RESULT_NOT_PUBLISHED
                    , "run " + runId + " is " + record.status());
        }
        return record;
    }

    /**
     * Identity of a submission: rule bytes plus the contents of every dataset it reads.
     *
     * <p>An earlier version hashed the source <em>directory</em> for file input. That was wrong in a way
     * no test caught until one did: re-exporting {@code amounts.csv} into the same directory produced the
     * same digest, so resubmitting with the same idempotency key returned the previous run's answer for
     * different numbers. Idempotency is a promise about the request, and a request is its data.
     *
     * <p>Amount rows supplied in memory displace their dataset, so their values are hashed instead of the
     * file's. Driver datasets always come from files, even for a preview, because a preview asks "what
     * would this rule do with these amounts" rather than "with made-up weights".
     */
    private String requestDigest(RuleResolver.RuleDocument rule, AllocationSpec spec, AmountSource source,
                                 AllocationInput input) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("rule:").append(rule.sha256());

        AmountSource.InMemory memory = source instanceof AmountSource.InMemory inMemory ? inMemory : null;
        List<String> amountNames = new ArrayList<>(spec.amountDatasets().keySet());
        Collections.sort(amountNames);
        for (String dataset : amountNames) {
            sb.append("\namounts:").append(dataset).append('=');
            if (memory != null && spec.amountDatasets().size() == 1) {
                // The single amount dataset is the one being replaced by the caller's rows.
                sb.append(rowsDigest(memory.rows()));
            } else {
                sb.append(Objects.toString(input.fingerprint(dataset, null), "absent"));
            }
        }

        TreeSet<String> references = new TreeSet<>();
        for (StageSpec stage : spec.stages()) {
            for (AllocationRule allocation : stage.allocations()) {
                references.add(allocation.driverDataset() + '\u0000' + allocation.weightField());
            }
        }
        for (String reference : references) {
            int split = reference.indexOf('\u0000');
            String dataset = reference.substring(0, split);
            String field = reference.substring(split + 1);
            sb.append("\ndrivers:").append(dataset).append('.').append(field).append('=')
                    .append(Objects.toString(input.fingerprint(dataset, field), "absent"));
        }
        return SpecCodec.sha256Hex(sb.toString());
    }

    /**
     * Digest of caller-supplied rows, in the order given.
     *
     * <p>Keys are sorted because {@link AmountRow} copies its coordinate into an immutable map whose
     * iteration order is unspecified; hashing that order would make the digest differ between runs of the
     * same request.
     */
    private static String rowsDigest(List<AmountRow> rows) {
        StringBuilder sb = new StringBuilder(rows.size() * 32);
        for (AmountRow row : rows) {
            List<String> keys = new ArrayList<>(row.coord().keySet());
            Collections.sort(keys);
            for (String key : keys) {
                sb.append(key).append('=').append(row.coord().get(key)).append(',');
            }
            sb.append('=').append(row.amount().format()).append('\n');
        }
        return SpecCodec.sha256Hex(sb.toString());
    }
}
