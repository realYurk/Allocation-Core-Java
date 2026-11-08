package io.allocation.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code run} command, driven through the CLI rather than the API.
 *
 * <p>The flag under test is the one that decides whether a run fits in its container: {@code --max-rows}
 * sets the spill budget. A budget of one row means every stage buffer writes to disk and reads back, so
 * this asserts the flags reach the engine and that the disk path changes nothing observable.
 */
class AllocationCliRunTest {

    private static final String RULE = """
            {
              "schema": "allocation-ir/2.0",
              "rounding_policy": "LARGEST_REMAINDER_IDENTITY",
              "name": "cli run",
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
                  "source": {
                    "grouping": {"plant": "AS_IS", "account": "TOTAL"},
                    "filters": {}
                  },
                  "target": {
                    "plant": {"mode": "SOURCE"},
                    "account": {"mode": "INCLUDE", "include": ["A1", "A2", "A3"]}
                  },
                  "conservation_keys": ["plant"],
                  "allocations": [
                    {"id": "r1", "share": "1", "driver_dataset": "drivers", "weight_field": "weight"}
                  ]
                }
              ]
            }
            """;

    private static final List<String> ARTEFACTS = List.of(
            "results.csv", "ledger.csv", "source_to_pool.csv", "reconciliation.csv", "lineage_links.csv");

    @TempDir
    Path directory;

    private Path writeInputs() throws IOException {
        Path input = Files.createDirectories(directory.resolve("input"));
        Files.writeString(input.resolve("rule.json"), RULE, StandardCharsets.UTF_8);
        Files.writeString(input.resolve("amounts.csv"),
                "plant,account,amount\nP1,SRC,100.00\nP2,SRC,33.33\nP2,SRC,0.01\n", StandardCharsets.UTF_8);
        Files.writeString(input.resolve("drivers.csv"),
                "plant,account,weight\nP1,A1,1\nP1,A2,2\nP1,A3,3\nP2,A1,1\nP2,A2,1\n", StandardCharsets.UTF_8);
        return input.resolve("rule.json");
    }

    private record Invocation(int status, String out, String err) {
    }

    private Invocation run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status;
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            status = AllocationCli.dispatch(args, outStream, errStream);
        }
        return new Invocation(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void spillingEveryRowChangesNothingVisible() throws IOException {
        Path rule = writeInputs();
        Path inMemory = directory.resolve("out-memory");
        Path spilled = directory.resolve("out-spilled");

        Invocation first = run("run", rule.toString(), inMemory.toString(), "--max-rows", "1000");
        assertEquals(0, first.status(), first.err());
        assertTrue(first.out().contains("status=PASS"), first.out());

        Invocation second = run("run", rule.toString(), spilled.toString(), "--max-rows", "1");
        assertEquals(0, second.status(), second.err());
        assertEquals(first.out(), second.out(), "the summary must not mention how the rows were buffered");

        for (String artefact : ARTEFACTS) {
            assertArrayEquals(Files.readAllBytes(inMemory.resolve(artefact)),
                    Files.readAllBytes(spilled.resolve(artefact)),
                    artefact + " must not depend on the spill budget");
        }
    }

    @Test
    void refusesToReuseAnExistingOutputDirectory() throws IOException {
        Path rule = writeInputs();
        Path output = directory.resolve("out");
        assertEquals(0, run("run", rule.toString(), output.toString()).status());
        Invocation again = run("run", rule.toString(), output.toString());
        assertEquals(1, again.status(), "reusing a directory must fail the command");
        assertTrue(again.err().contains("already exists"), again.err());
    }

    @Test
    void printsUsageWithoutArguments() {
        Invocation result = run("run");
        assertEquals(2, result.status());
        assertTrue(result.err().contains("--max-rows"), result.err());
        assertFalse(result.out().contains("status="), result.out());
    }
}
