package io.allocation.kernel;

import io.allocation.kernel.exec.AllocationRunner;
import io.allocation.kernel.exec.Executor;
import io.allocation.kernel.exec.RunSummary;
import io.allocation.kernel.money.Decimal;
import io.allocation.kernel.plan.Plan;
import io.allocation.kernel.plan.Planner;
import io.allocation.kernel.reconcile.Reconciler;
import io.allocation.kernel.reconcile.ReferenceCsv;
import io.allocation.kernel.spec.AllocationSpec;
import io.allocation.kernel.spec.SpecCodec;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Command-line front end for the kernel.
 *
 * <p>Exists so the engine can be exercised, benchmarked and diffed without a host application. It is
 * also the surface used by the conformance harness to compare this implementation against frozen
 * reference output.
 *
 * <pre>
 *   validate  &lt;rule.json&gt;                  structural validation only
 *   inspect   &lt;rule.json&gt;                  print a normalised view of the plan
 *   run       &lt;rule.json&gt; &lt;outputDir&gt; [--workers N]
 *   reconcile --actual &lt;csv&gt; --reference &lt;csv&gt; [options]
 *   version
 * </pre>
 *
 * <p>Exit status of {@code reconcile}: 0 match, 3 differences, 1 blocked, 2 usage.
 */
public final class AllocationCli {

    /** Version reported by {@code version} and recorded in run summaries. */
    public static final String VERSION = "1.0.0";

    /** Exit status used by {@code reconcile} when the two sides disagree. */
    public static final int EXIT_DIFFERENCES = 3;

    private AllocationCli() {
    }

    /** Entry point. */
    public static void main(String[] args) {
        int status = dispatch(args, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    /** Runs one command and returns the process exit status. */
    public static int dispatch(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            err.println("usage: validate|inspect|run|reconcile|version");
            return 2;
        }
        try {
            switch (args[0]) {
                case "version" -> {
                    out.println("allocation-core-java " + VERSION);
                    return 0;
                }
                case "validate" -> {
                    if (args.length < 2) {
                        err.println("usage: validate <rule.json>");
                        return 2;
                    }
                    AllocationSpec spec = read(args[1]);
                    Plan plan = Planner.compile(spec);
                    out.println("OK " + plan.ordered().size() + " stage(s), "
                            + spec.dimensions().size() + " dimension(s), precision " + spec.precision());
                    return 0;
                }
                case "inspect" -> {
                    if (args.length < 2) {
                        err.println("usage: inspect <rule.json>");
                        return 2;
                    }
                    AllocationSpec spec = read(args[1]);
                    Plan plan = Planner.compile(spec);
                    out.println(spec.name() + " [" + spec.schema() + "] precision=" + spec.precision());
                    out.println("dimensions: " + String.join(", ", spec.dimensions()));
                    for (String stageId : plan.stageIds()) {
                        out.println("stage " + stageId + " -> " + plan.outgoing().getOrDefault(stageId, java.util.List.of()));
                    }
                    return 0;
                }
                case "run" -> {
                    return runCommand(args, out, err);
                }
                case "reconcile" -> {
                    return reconcileCommand(args, out, err);
                }
                default -> {
                    err.println("unknown command: " + args[0]);
                    return 2;
                }
            }
        } catch (UsageException e) {
            err.println(e.getMessage());
            return 2;
        } catch (AllocationException e) {
            err.println(e.code().code() + " " + e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            err.println("INTERNAL " + e);
            return 1;
        }
    }

    private static int runCommand(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 3) {
            err.println("usage: run <rule.json> <outputDir> [--workers N]");
            return 2;
        }
        int workers = 1;
        for (int i = 3; i < args.length; i++) {
            if ("--workers".equals(args[i]) && i + 1 < args.length) {
                workers = Integer.parseInt(args[++i]);
            }
        }
        Path rule = Path.of(args[1]);
        Path output = Path.of(args[2]);
        RunSummary summary = AllocationRunner.run(rule, output, new Executor.Options(workers, false));
        out.println("status=" + summary.status()
                + " input=" + summary.inputAmount()
                + " output=" + summary.outputAmount()
                + " pools=" + summary.poolChecks()
                + " rows=" + summary.terminalRows());
        for (RunSummary.StageSummary stage : summary.stages()) {
            out.println("  " + stage.stage() + ": in=" + stage.input() + " out=" + stage.output()
                    + " pools=" + stage.pools() + " rows=" + stage.outputRows());
        }
        return 0;
    }

    /**
     * Compares a published result file against an independently supplied reference.
     *
     * <p>Nothing is allocated here and neither input is modified. The command exists because "the total
     * matches" is not evidence — only a coordinate-level comparison against a second implementation is.
     */
    private static int reconcileCommand(String[] args, PrintStream out, PrintStream err) {
        String actualPath = null;
        String referencePath = null;
        String differencesPath = null;
        String toleranceText = "0";
        int precision = 2;
        boolean rejectAll = false;
        boolean rejectActual = false;
        boolean rejectReference = false;

        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            switch (flag) {
                case "--actual" -> actualPath = value(args, ++i, flag);
                case "--reference" -> referencePath = value(args, ++i, flag);
                case "--out" -> differencesPath = value(args, ++i, flag);
                case "--tolerance" -> toleranceText = value(args, ++i, flag);
                case "--precision" -> precision = Integer.parseInt(value(args, ++i, flag));
                case "--reject-duplicates" -> rejectAll = true;
                case "--reject-actual-duplicates" -> rejectActual = true;
                case "--reject-reference-duplicates" -> rejectReference = true;
                default -> throw new UsageException("unknown option: " + flag);
            }
        }
        if (actualPath == null || referencePath == null) {
            err.println("usage: reconcile --actual <results.csv> --reference <reference.csv>"
                    + " [--precision 0..6] [--tolerance 0] [--out differences.csv]"
                    + " [--reject-duplicates] [--reject-actual-duplicates] [--reject-reference-duplicates]");
            return 2;
        }

        ReferenceCsv.Table actual = ReferenceCsv.load(Path.of(actualPath), precision);
        ReferenceCsv.Table reference = ReferenceCsv.load(Path.of(referencePath), precision);
        List<String> dimensions = ReferenceCsv.commonDimensions(actual, reference);

        long tolerance = Decimal.parseScaled(toleranceText, precision);
        if (tolerance < 0) {
            throw new AllocationException(ErrorCode.RULE_INVALID, "tolerance must be nonnegative");
        }
        Reconciler.Options options = new Reconciler.Options(tolerance,
                rejectAll ? Reconciler.DuplicatePolicy.REJECT : Reconciler.DuplicatePolicy.AGGREGATE);

        Map<String, Long> actualIndex = Reconciler.index(actual.rows(),
                rejectAll || rejectActual ? Reconciler.DuplicatePolicy.REJECT : Reconciler.DuplicatePolicy.AGGREGATE);
        Map<String, Long> referenceIndex = Reconciler.index(reference.rows(),
                rejectAll || rejectReference ? Reconciler.DuplicatePolicy.REJECT : Reconciler.DuplicatePolicy.AGGREGATE);

        Reconciler.Report report = Reconciler.describe(
                Reconciler.compare(referenceIndex, actualIndex, options), dimensions);

        out.println("status=" + report.status()
                + " matched=" + report.matched()
                + " different=" + report.differed()
                + " missing=" + report.missing()
                + " extra=" + report.extra());
        out.println("actual_rows=" + actual.rowCount()
                + " duplicate_rows=" + (actual.rowCount() - actualIndex.size())
                + " total=" + Decimal.format(report.actualTotal(), precision));
        out.println("reference_rows=" + reference.rowCount()
                + " duplicate_rows=" + (reference.rowCount() - referenceIndex.size())
                + " total=" + Decimal.format(report.expectedTotal(), precision));
        out.println("actual_sha256=" + actual.sha256());
        out.println("reference_sha256=" + reference.sha256());

        for (Reconciler.Account account : Reconciler.accounts(report)) {
            out.println("  account " + account.account() + ": different=" + account.differed()
                    + " missing=" + account.missing() + " extra=" + account.extra());
        }
        for (Reconciler.Difference difference : Reconciler.top(report, 20)) {
            out.println("  " + difference.kind() + " " + difference.coordinate()
                    + " reference=" + Decimal.format(difference.expected(), precision)
                    + " actual=" + Decimal.format(difference.actual(), precision)
                    + " delta=" + Decimal.format(difference.delta(), precision));
        }

        if (differencesPath != null) {
            try {
                Files.writeString(Path.of(differencesPath),
                        Reconciler.toCsv(report, precision, dimensions), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new AllocationException(ErrorCode.INTERNAL, "cannot write " + differencesPath, e);
            }
        }
        return report.matchedExactly() ? 0 : EXIT_DIFFERENCES;
    }

    private static String value(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new UsageException(flag + " requires a value");
        }
        return args[index];
    }

    /** Thrown for a malformed command line; maps to exit status 2. */
    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    private static AllocationSpec read(String path) {
        try {
            String text = Files.readString(Path.of(path), StandardCharsets.UTF_8);
            return SpecCodec.parse(text);
        } catch (IOException e) {
            throw new AllocationException(ErrorCode.RULE_INVALID, "cannot read " + path, e);
        }
    }
}