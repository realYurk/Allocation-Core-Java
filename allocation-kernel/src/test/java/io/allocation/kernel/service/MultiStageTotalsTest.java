package io.allocation.kernel.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a two-stage run says it moved.
 *
 * <p>This is a regression test for a bug that was invisible for as long as every test used one stage: the
 * sink summed the per-pool reconciliation lines and called that the run's input. Each pool's input is an
 * amount entering that stage, so a two-stage rule counted every amount twice and reported exactly double;
 * a three-stage rule would report triple. The numbers still balanced (input == output), which is why nothing
 * complained — the run simply claimed to have allocated twice as much as it had.
 *
 * <p>The correct figure is the executor's: the DAG has one input, and it is counted once when the sources
 * are seeded.
 */
class MultiStageTotalsTest {

    private static final String RULE = """
            {
              "schema": "allocation-ir/2.0",
              "rounding_policy": "LARGEST_REMAINDER_IDENTITY",
              "name": "two stages, one amount",
              "precision": 2,
              "dimensions": ["plant", "dept", "account"],
              "immutable_dimensions": [],
              "hierarchies": {},
              "datasets": {
                "amounts": {"amounts": "amounts.csv"},
                "drivers": {"dept-weight": "dept-weight.csv", "account-weight": "account-weight.csv"}
              },
              "stages": [
                {
                  "id": "to-dept",
                  "inputs": [{"from": "amounts", "share": "1"}],
                  "source": {"grouping": {"plant": "AS_IS", "dept": "TOTAL", "account": "AS_IS"}, "filters": {}},
                  "target": {"plant": {"mode": "SOURCE"},
                             "dept": {"mode": "INCLUDE", "include": ["D1", "D2"]},
                             "account": {"mode": "SOURCE"}},
                  "conservation_keys": ["plant", "account"],
                  "allocations": [
                    {"id": "by-dept", "share": "1", "driver_dataset": "dept-weight", "weight_field": "weight"}
                  ]
                },
                {
                  "id": "to-account",
                  "inputs": [{"from": "to-dept", "share": "1"}],
                  "source": {"grouping": {"plant": "AS_IS", "dept": "AS_IS", "account": "TOTAL"}, "filters": {}},
                  "target": {"plant": {"mode": "SOURCE"}, "dept": {"mode": "SOURCE"},
                             "account": {"mode": "INCLUDE", "include": ["MATERIAL", "LABOUR"]}},
                  "conservation_keys": ["plant", "dept"],
                  "allocations": [
                    {"id": "by-account", "share": "1", "driver_dataset": "account-weight",
                     "weight_field": "weight"}
                  ]
                }
              ]
            }
            """;

    @TempDir
    Path directory;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(directory.resolve("rule.json"), RULE, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("amounts.csv"),
                "plant,dept,account,amount\nP1,TOTAL,OVERHEAD,100.00\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("dept-weight.csv"),
                "plant,dept,weight\nP1,D1,1\nP1,D2,3\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("account-weight.csv"),
                "plant,dept,account,weight\nP1,D1,MATERIAL,1\nP1,D1,LABOUR,1\n"
                        + "P1,D2,MATERIAL,1\nP1,D2,LABOUR,1\n", StandardCharsets.UTF_8);
    }

    @Test
    void aTwoStageRunReportsItsInputOnceNotOncePerStage() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        DefaultAllocationTemplate template = new DefaultAllocationTemplate(repository,
                new DirectoryRuleResolver(directory), directory.resolve("work"));
        try {
            AllocationTemplate.SubmitResult submitted = template.submit(RunCommand.builder()
                    .ruleCode("rule")
                    .source(new AmountSource.Csv(directory))
                    .build());
            RunResult result = template.await(submitted.runId(), Duration.ofSeconds(10));

            RunRepository.RunRecord record = repository.find(submitted.runId()).orElseThrow();
            assertEquals(10_000L, record.inputAmount(), "100.00 entered the DAG once");
            assertEquals(10_000L, record.outputAmount());
            assertEquals("100.00", result.conservation().input().format(),
                    "the run moved 100.00, not 200.00");
            assertTrue(result.conservation().balanced());
            assertEquals(4, result.total(), "2 depts x 2 accounts");
        } finally {
            template.close();
        }
    }

    @Test
    void thePoolsOfEveryStageAreStillRecordedIndividually() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        DefaultAllocationTemplate template = new DefaultAllocationTemplate(repository,
                new DirectoryRuleResolver(directory), directory.resolve("work"));
        try {
            AllocationTemplate.SubmitResult submitted = template.submit(RunCommand.builder()
                    .ruleCode("rule")
                    .source(new AmountSource.Csv(directory))
                    .build());
            template.await(submitted.runId(), Duration.ofSeconds(10));

            // 1 pool in stage 1 + 2 pools in stage 2. Per-stage evidence is where the stage-level figures
            // live; the run record is the sum of the DAG, and the two must not be confused.
            List<RunRepository.PoolRecord> pools = repository.pools(submitted.runId());
            assertEquals(3, pools.size());

            Map<String, Long> perStage = new HashMap<>();
            for (RunRepository.PoolRecord pool : pools) {
                perStage.merge(pool.stageId(), pool.inputAmount(), Long::sum);
            }
            assertEquals(Map.of("to-dept", 10_000L, "to-account", 10_000L), perStage,
                    "each stage moved 100.00 in total");

            Map<String, Long> stageTwo = new HashMap<>();
            for (RunRepository.PoolRecord pool : pools) {
                if ("to-account".equals(pool.stageId())) {
                    stageTwo.put(pool.poolKey(), pool.inputAmount());
                }
            }
            assertEquals(2, stageTwo.size());
            assertTrue(stageTwo.containsValue(2_500L), "D1 got 25.00: " + stageTwo);
            assertTrue(stageTwo.containsValue(7_500L), "D2 got 75.00: " + stageTwo);
        } finally {
            template.close();
        }
    }
}
