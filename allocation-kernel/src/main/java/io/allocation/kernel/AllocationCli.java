package io.allocation.kernel;

import io.allocation.kernel.exec.AllocationRunner;
import io.allocation.kernel.exec.Executor;
import io.allocation.kernel.exec.RunSummary;
import io.allocation.kernel.plan.Plan;
import io.allocation.kernel.plan.Planner;
import io.allocation.kernel.spec.AllocationSpec;
import io.allocation.kernel.spec.SpecCodec;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line front end for the kernel.
 *
 * <p>Exists so the engine can be exercised, benchmarked and diffed without a host application. It is
 * also the surface used by the conformance harness to compare this implementation against frozen
 * reference output.
 *
 * <pre>
 *   validate &lt;rule.json&gt;                  structural validation only
 *   inspect  &lt;rule.json&gt;                  print a normalised view of the plan
 *   run      &lt;rule.json&gt; &lt;outputDir&gt; [--workers N]
 *   version
 * </pre>
 */
public final class AllocationCli {

    /** Version reported by {@code version} and recorded in run summaries. */
    public static final String VERSION = "1.0.0";

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
            err.println("usage: validate|inspect|run|version");
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
                default -> {
                    err.println("unknown command: " + args[0]);
                    return 2;
                }
            }
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

    private static AllocationSpec read(String path) {
        try {
            String text = Files.readString(Path.of(path), StandardCharsets.UTF_8);
            return SpecCodec.parse(text);
        } catch (IOException e) {
            throw new AllocationException(ErrorCode.RULE_INVALID, "cannot read " + path, e);
        }
    }
}
