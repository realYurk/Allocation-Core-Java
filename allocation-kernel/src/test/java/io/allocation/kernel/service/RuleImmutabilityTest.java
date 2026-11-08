package io.allocation.kernel.service;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A run is explained by the rule it executed, not by whatever rule its code names today.
 *
 * <p>These tests pin the invariant that D-035 introduced: the rule document travels with the run and its
 * digest is verified on every read. Before that, {@code specOf} looked the rule up by code, so a rule
 * republished with a different precision made stored integers print at the wrong magnitude, and reordered
 * dimensions relabelled every coordinate. The first test proves history survives a change; the second
 * proves a tampered row is caught instead of rendered.
 */
class RuleImmutabilityTest {

    private static final String RULE = """
            {
              "schema": "allocation-ir/2.0",
              "rounding_policy": "LARGEST_REMAINDER_IDENTITY",
              "name": "immutability",
              "precision": 2,
              "dimensions": ["plant", "account"],
              "immutable_dimensions": [],
              "hierarchies": {},
              "datasets": {
                "amounts": {"amounts": "amounts.csv"},
                "drivers": {"drivers": "drivers.csv"}
              },
              "stages": [
                {
                  "id": "st1",
                  "inputs": [{"from": "amounts", "share": "1"}],
                  "source": {"grouping": {"plant": "AS_IS", "account": "TOTAL"}, "filters": {}},
                  "target": {"plant": {"mode": "SOURCE"}, "account": {"mode": "INCLUDE", "include": ["A1", "A2"]}},
                  "conservation_keys": ["plant"],
                  "allocations": [
                    {"id": "r1", "share": "1", "driver_dataset": "drivers", "weight_field": "weight"}
                  ]
                }
              ]
            }
            """;

    @Test
    void aRunExplainsItselfAfterItsRuleChangesOnDisk(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("rule.json"), RULE, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("amounts.csv"), "plant,account,amount\nP1,SRC,100.00\n",
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("drivers.csv"), "plant,account,weight\nP1,A1,1\nP1,A2,1\n",
                StandardCharsets.UTF_8);

        InMemoryRunRepository repository = new InMemoryRunRepository();
        DefaultAllocationTemplate template = new DefaultAllocationTemplate(repository,
                new DirectoryRuleResolver(directory), directory.resolve("work"));
        try {
            AllocationTemplate.SubmitResult submitted = template.submit(RunCommand.builder()
                    .ruleCode("rule")
                    .source(new AmountSource.Csv(directory))
                    .build());
            template.await(submitted.runId(), Duration.ofSeconds(10));

            // Republish the rule with a different precision. If the run were still explained by code,
            // this is the moment its history silently becomes wrong: the stored 100.00 (10000 in minor
            // units of precision 2) would now print at precision 4 as 1.0000, or the other way around.
            Files.writeString(directory.resolve("rule.json"),
                    RULE.replace("\"precision\": 2", "\"precision\": 4"), StandardCharsets.UTF_8);

            RunResult result = template.result(submitted.runId(), 1, 10);
            assertEquals("50.00", result.lines().get(0).amount().format(),
                    "the run must label its stored integers with the precision it executed with");
        } finally {
            template.close();
        }
    }

    @Test
    void aRowWhoseRuleDoesNotHashToItsDigestIsRefusedNotRendered(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("rule.json"), RULE, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("amounts.csv"), "plant,account,amount\nP1,SRC,100.00\n",
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("drivers.csv"), "plant,account,weight\nP1,A1,1\nP1,A2,1\n",
                StandardCharsets.UTF_8);

        InMemoryRunRepository repository = new InMemoryRunRepository();
        DefaultAllocationTemplate template = new DefaultAllocationTemplate(repository,
                new DirectoryRuleResolver(directory), directory.resolve("work"));
        try {
            AllocationTemplate.SubmitResult submitted = template.submit(RunCommand.builder()
                    .ruleCode("rule")
                    .source(new AmountSource.Csv(directory))
                    .build());
            template.await(submitted.runId(), Duration.ofSeconds(10));

            // Simulate a row edited by hand with a SQL client: the stored document no longer matches the
            // digest the run recorded. Answering with these bytes is a wrong number that looks right.
            RunRepository.RunRecord record = repository.find(submitted.runId()).orElseThrow();
            RunRepository.RunRecord tampered = new RunRepository.RunRecord(record.runId(), record.ruleCode(),
                    record.ruleSha256(), RULE.replace("\"precision\": 2", "\"precision\": 3"),
                    record.idempotencyKey(), record.requestSha256(), record.precision(), record.status(),
                    record.inputAmount(), record.outputAmount(), record.poolCount(), record.rowCount(),
                    record.failureCode(), record.failureMessage(), record.startedAtEpochMs(),
                    record.finishedAtEpochMs());
            repository.update(tampered);

            AllocationException e = assertThrows(AllocationException.class,
                    () -> template.result(submitted.runId(), 1, 10));
            assertEquals(ErrorCode.RULE_DIGEST_MISMATCH, e.code());
        } finally {
            template.close();
        }
    }
}
