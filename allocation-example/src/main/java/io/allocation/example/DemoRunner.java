package io.allocation.example;

import io.allocation.kernel.service.AllocationTemplate;
import io.allocation.kernel.service.Money;
import io.allocation.kernel.service.AmountSource;
import io.allocation.kernel.service.Explain;
import io.allocation.kernel.service.Preview;
import io.allocation.kernel.service.ReconcileCommand;
import io.allocation.kernel.service.RunCommand;
import io.allocation.kernel.service.RunResult;
import io.allocation.spring.AllocationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs the sample allocation at startup and prints what came back.
 *
 * <p>An example that only exposes endpoints requires the reader to write a client before they learn
 * anything. This one submits the sample rule, waits for the run, prints the totals and the first few lines,
 * shows the decision chain for one coordinate, and — if the frozen reference is present — reconciles
 * against it. Everything printed here is produced by the same code path a caller over HTTP would use.
 *
 * <p>Turn it off with {@code example.demo=false}, which is what the HTTP test does so that it can drive
 * the contract itself.
 */
@Component
@ConditionalOnProperty(prefix = "example", name = "demo", havingValue = "true", matchIfMissing = true)
public class DemoRunner implements ApplicationRunner {

    /** The rule code is the rule file's name without {@code .json}. */
    private static final String RULE_CODE = "cost-allocation";

    private static final int LINES_TO_PRINT = 6;

    /** Driver weights are scaled to six places by the rule language; printing them as integers is unkind. */
    private static final int WEIGHT_SCALE = 6;

    private final AllocationTemplate engine;
    private final AllocationProperties properties;
    private final ExampleRun state;
    private final String reference;
    private final String port;

    public DemoRunner(AllocationTemplate engine, AllocationProperties properties, ExampleRun state,
                      @Value("${example.reference:data/reference/results.csv}") String reference,
                      @Value("${server.port:8080}") String port) {
        this.engine = engine;
        this.properties = properties;
        this.state = state;
        this.reference = reference;
        this.port = port;
    }

    @Override
    public void run(ApplicationArguments args) {
        System.out.println();
        System.out.println("=== allocation example: two-pass manufacturing overhead ===");

        AllocationTemplate.SubmitResult submitted = engine.submit(RunCommand.builder()
                .ruleCode(RULE_CODE)
                .source(new AmountSource.Csv(properties.getRulesDirectory()))
                // A fixed key makes a demo restart into a replay instead of a second set of rows.
                .idempotencyKey("example-demo")
                .build());
        state.ran(submitted.runId());
        System.out.printf("submitted %s (replay=%s)%n", submitted.runId(), submitted.idempotentReplay());

        engine.await(submitted.runId(), Duration.ofMinutes(2));
        // await() returns a single line; ask for a page so the demo has something to show.
        RunResult result = engine.result(submitted.runId(), 1, LINES_TO_PRINT);
        Preview.Conservation conservation = result.conservation();
        System.out.printf("status=%s  input=%s  output=%s  balanced=%s  rows=%d%n",
                result.status(), conservation.input().format(), conservation.output().format(),
                conservation.balanced(), result.total());

        System.out.println();
        System.out.println("first " + LINES_TO_PRINT + " result lines:");
        for (Preview.AllocatedLine line : result.lines().subList(0, Math.min(LINES_TO_PRINT, result.lines().size()))) {
            System.out.printf("  %-44s %12s%n", line.coord(), line.amount().format());
        }

        // One coordinate, all the way down: which stage, which rule, which weight, which rank.
        Map<String, String> one = new LinkedHashMap<>();
        one.put("period", "2026-01");
        one.put("plant", "P1");
        one.put("dept", "ASSEMBLY");
        one.put("account", "MATERIAL");
        Explain explain = engine.explain(submitted.runId(), one);
        System.out.println();
        System.out.println("why " + one + " got its number:");
        for (Explain.Step step : explain.steps()) {
            System.out.printf("  stage=%s rule=%s%n    pool=%s of %s%n    weight=%s/%s rank=%d "
                            + "residual=%s%n    -> %s%n",
                    step.stageId(), step.ruleId(), step.poolKey(), step.poolAmount().format(),
                    new Money(step.weight(), WEIGHT_SCALE).format(),
                    new Money(step.denominator(), WEIGHT_SCALE).format(),
                    step.remainderRank(), step.gotResidual(), step.allocatedAmount().format());
        }

        reconcile(submitted.runId());
        System.out.println();
        System.out.println("try it over HTTP:");
        System.out.printf("  curl -s localhost:%s/allocation/runs/%s%n", port, submitted.runId());
        System.out.printf("  curl -s 'localhost:%s/allocation/runs/%s/result?page=1&size=10'%n",
                port, submitted.runId());
        System.out.println();
    }

    /**
     * Compares the run against the frozen file checked in beside the sample data.
     *
     * <p>This is the same call the README's step 10 asks a migrating team to make against their legacy
     * system. Here the "legacy system" is this rule's own output as it was the first time the sample ran,
     * which turns the comparison into a golden-file test: if a change to the engine moves one of those 36
     * amounts, the demo says so instead of quietly printing different numbers.
     */
    private void reconcile(String runId) {
        Path path = Path.of(reference);
        if (!Files.isReadable(path)) {
            System.out.println();
            System.out.println("(no reference at " + path.toAbsolutePath() + ", skipping reconcile)");
            return;
        }
        AllocationTemplate.ReconcileOutcome outcome = engine.reconcile(ReconcileCommand.builder()
                .runId(runId)
                .reference(path)
                .build());
        state.reconciled(outcome.summary().status(), outcome.differencesPath());
        System.out.println();
        System.out.printf("reconcile vs %s: status=%s matched=%d differed=%d missing=%d extra=%d%n",
                reference, outcome.summary().status(), outcome.summary().matched(),
                outcome.summary().differed(), outcome.summary().missing(), outcome.summary().extra());
        System.out.println("differences file: " + outcome.differencesPath());
    }
}
