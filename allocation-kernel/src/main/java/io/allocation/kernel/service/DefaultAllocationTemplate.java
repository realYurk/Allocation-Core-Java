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
import io.allocation.kernel.money.Apportion;
import io.allocation.kernel.money.Decimal;
import io.allocation.kernel.plan.Plan;
import io.allocation.kernel.plan.Planner;
import io.allocation.kernel.reconcile.Reconciler;
import io.allocation.kernel.reconcile.ReferenceCsv;
import io.allocation.kernel.spec.AllocationRule;
import io.allocation.kernel.spec.AllocationSpec;
import io.allocation.kernel.spec.SpecCodec;
import io.allocation.kernel.spec.StageSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

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
 * executor holds for a stage, not by the size of its output. The remaining known limit is that progress
 * is reported at completion rather than continuously; that needs callbacks from the executor and changes
 * nothing in the contract above.
 */
public final class DefaultAllocationTemplate implements AllocationTemplate, AutoCloseable {

    /** JDK logger, so the kernel keeps its zero-dependency promise and a host can still see failures. */
    private static final System.Logger LOG = System.getLogger(DefaultAllocationTemplate.class.getName());

    private final RunRepository repository;
    private final RuleResolver rules;
    private final Path workingDirectory;
    private final Semaphore admission;
    private final ExecutorService workers;

    public DefaultAllocationTemplate(RunRepository repository, RuleResolver rules, Path workingDirectory) {
        this(repository, rules, workingDirectory, 16);
    }

    public DefaultAllocationTemplate(RunRepository repository, RuleResolver rules, Path workingDirectory,
                                     int queueCapacity) {
        this.repository = repository;
        this.rules = rules;
        this.workingDirectory = workingDirectory.toAbsolutePath();
        this.admission = new Semaphore(queueCapacity);
        this.workers = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("allocation-run-", 0).factory());
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
            Optional<RunRepository.RunRecord> existing = repository.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                RunRepository.RunRecord record = existing.get();
                if (!record.requestSha256().equals(requestDigest)) {
                    throw new AllocationException(ErrorCode.IDEMPOTENCY_CONFLICT,
                            "idempotency key " + key + " was used for a different rule or input");
                }
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
                    return new SubmitResult(winner.get().runId(), winner.get().status(), true);
                }
            }
            throw e;
        }
        workers.execute(() -> {
            try {
                executeRun(pending, rule, spec, command, input);
            } finally {
                admission.release();
            }
        });
        return new SubmitResult(pending.runId(), RunRepository.Status.PENDING, false);
    }

    @Override
    public RunView status(String runId) {
        RunRepository.RunRecord record = requireRun(runId);
        List<RunRepository.PoolRecord> pools = repository.pools(runId);
        long elapsed = (record.finishedAtEpochMs() == null ? System.currentTimeMillis()
                : record.finishedAtEpochMs()) - record.startedAtEpochMs();
        return new RunView(runId, record.status(),
                new RunView.Progress(pools.size(), record.poolCount(), record.rowCount()),
                record.startedAtEpochMs(), Math.max(0L, elapsed));
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
        Map<String, Decision> decisions = decisions(spec, repository.lines(runId));
        List<Preview.AllocatedLine> lines = new ArrayList<>();
        for (RunSink.ResultLine line : repository.results(runId, (page - 1) * size, size)) {
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
        for (Decision decision : decisions(spec, repository.lines(runId)).values()) {
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
        ReferenceCsv.Table reference = ReferenceCsv.load(command.reference(), spec);

        List<Map.Entry<String, Long>> produced = new ArrayList<>();
        for (RunSink.ResultLine line : repository.results(command.runId(), 0, Integer.MAX_VALUE)) {
            produced.add(new AbstractMap.SimpleImmutableEntry<>(line.coord().key(), line.amount()));
        }
        Map<String, Long> actual = Reconciler.index(produced, Reconciler.DuplicatePolicy.AGGREGATE);
        Map<String, Long> expected = Reconciler.index(reference.rows(), command.duplicates());
        long tolerance = Decimal.parseScaled(command.tolerance(), spec.precision());

        Reconciler.Report report = Reconciler.describe(Reconciler.compare(expected, actual,
                new Reconciler.Options(tolerance, command.duplicates())), spec.dimensions());
        Path target = workingDirectory.resolve("reconcile").resolve(command.runId()).resolve("differences.csv");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, Reconciler.toCsv(report, spec.precision(), spec.dimensions()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AllocationException(ErrorCode.INTERNAL, "cannot write " + target, e);
        }
        return new ReconcileOutcome(report, target.toString());
    }

    @Override
    public void close() {
        workers.shutdown();
    }

    // ---------------------------------------------------------------- execution

    private void executeRun(RunRepository.RunRecord pending, RuleResolver.RuleDocument rule,
                            AllocationSpec spec, RunCommand command, AllocationInput data) {
        repository.update(pending.withStatus(RunRepository.Status.RUNNING));
        RunRepository.EvidenceWriter writer = repository.openEvidence(pending.runId());
        try {
            Plan plan = Planner.compile(spec);
            RepositorySink sink = new RepositorySink(writer, command.options().retainResultLines());
            Executor.execute(plan, rule.sha256(), data, sink,
                    new Executor.Options(command.options().workers(), command.options().failOnMissingTarget()));

            // Evidence first, status second. A poller that sees PUBLISHED can therefore always read a
            // complete result set, which is the only ordering that makes the status meaningful.
            writer.commit();
            repository.update(pending.published(sink.inputAmount(), sink.outputAmount(),
                    (int) sink.poolRows(), sink.resultRows(), System.currentTimeMillis()));
        } catch (AllocationException e) {
            recordFailure(pending, e.code().code(), truncate(e.getMessage()), e);
        } catch (RuntimeException e) {
            recordFailure(pending, ErrorCode.INTERNAL.code(), truncate(String.valueOf(e)), e);
        } finally {
            // No-op after a successful commit; otherwise it discards whatever the failed run wrote.
            writer.close();
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
    private void recordFailure(RunRepository.RunRecord pending, String code, String message, RuntimeException cause) {
        try {
            repository.update(pending.failed(code, message, System.currentTimeMillis()));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR,
                    "run " + pending.runId() + " failed with " + code + " and could not be marked failed: " + e,
                    cause);
        }
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
        AmountSource.InMemory memory = (AmountSource.InMemory) source;
        return previewInput(rule, spec, singleAmountDataset(spec), memory.rows());
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

    private RunRepository.RunRecord requireRun(String runId) {
        return repository.find(runId).orElseThrow(() -> new AllocationException(ErrorCode.RULE_INVALID,
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
