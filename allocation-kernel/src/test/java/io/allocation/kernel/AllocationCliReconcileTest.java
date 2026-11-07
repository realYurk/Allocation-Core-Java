package io.allocation.kernel;

import io.allocation.kernel.exec.AllocationRunner;
import io.allocation.kernel.exec.Executor;
import io.allocation.kernel.exec.RunSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@code reconcile} over a real run, so the command is tested against the file the engine
 * actually writes rather than against a hand-made fixture.
 *
 * <p>The interesting detail is that {@code results.csv} carries {@code terminal_stage} and
 * {@code row_id} columns. If the reader treated those as business coordinates, every comparison
 * against an external export would fail — so this is worth an end-to-end test instead of a unit test
 * of the header parser alone.
 */
class AllocationCliReconcileTest {

    private static final String RULE = """
            {
              "schema": "allocation-ir/2.0",
              "rounding_policy": "LARGEST_REMAINDER_IDENTITY",
              "name": "cli reconcile",
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
                    "account": {"mode": "INCLUDE", "include": ["A1", "A2"]}
                  },
                  "conservation_keys": ["plant"],
                  "allocations": [
                    {"id": "r1", "share": "1", "driver_dataset": "drivers", "weight_field": "weight"}
                  ]
                }
              ]
            }
            """;

    @TempDir
    Path directory;

    private Path outputDir;

    @BeforeEach
    void runAllocation() throws IOException {
        Path input = Files.createDirectories(directory.resolve("input"));
        Files.writeString(input.resolve("rule.json"), RULE, StandardCharsets.UTF_8);
        Files.writeString(input.resolve("amounts.csv"), "plant,account,amount\nP1,SRC,100.00\n",
                StandardCharsets.UTF_8);
        Files.writeString(input.resolve("drivers.csv"),
                "plant,account,weight\nP1,A1,1\nP1,A2,1\n", StandardCharsets.UTF_8);
        outputDir = directory.resolve("out");
        RunSummary summary = AllocationRunner.run(input.resolve("rule.json"), outputDir,
                new Executor.Options(1, false));
        assertEquals("PASS", summary.status());
    }

    private record Invocation(int status, String out, String err) {
    }

    private Invocation reconcile(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status;
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            status = AllocationCli.dispatch(args, outStream, errStream);
        }
        return new Invocation(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path reference(String content) throws IOException {
        Path path = directory.resolve("reference.csv");
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    @Test
    void reconcilesItsOwnOutput() throws IOException {
        Path reference = reference("account,plant,value\nA1,P1,50.00\nA2,P1,50.00\n");

        Invocation result = reconcile("reconcile",
                "--actual", outputDir.resolve("results.csv").toString(),
                "--reference", reference.toString(),
                "--precision", "2");

        assertEquals(0, result.status(), result.err());
        assertTrue(result.out().contains("status=MATCH"), result.out());
        assertTrue(result.out().contains("matched=2"), result.out());
        assertTrue(result.out().contains("actual_sha256="), result.out());
    }

    @Test
    void reportsDifferencesAndExitsWithThree() throws IOException {
        Path reference = reference("account,plant,value\nA1,P1,50.00\nA2,P1,49.99\n");
        Path differences = directory.resolve("differences.csv");

        Invocation result = reconcile("reconcile",
                "--actual", outputDir.resolve("results.csv").toString(),
                "--reference", reference.toString(),
                "--precision", "2",
                "--out", differences.toString());

        assertEquals(AllocationCli.EXIT_DIFFERENCES, result.status());
        assertTrue(result.out().contains("status=DIFFERENCES"), result.out());
        assertTrue(result.out().contains("different=1"), result.out());
        String csv = Files.readString(differences, StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("kind,account,coordinate,reference_amount,actual_amount,difference\n"), csv);
        // Dimensions are sorted for file-to-file comparison, so the rendering follows that order.
        assertTrue(csv.contains("DIFFERENT,A2,account=A2 | plant=P1,49.99,50.00,0.01"), csv);
    }

    @Test
    void toleranceAbsorbsACent() throws IOException {
        Path reference = reference("account,plant,value\nA1,P1,50.00\nA2,P1,49.99\n");

        Invocation result = reconcile("reconcile",
                "--actual", outputDir.resolve("results.csv").toString(),
                "--reference", reference.toString(),
                "--precision", "2",
                "--tolerance", "0.01");

        assertEquals(0, result.status(), result.out());
        assertTrue(result.out().contains("status=MATCH"), result.out());
    }

    @Test
    void treatsCoordinatesOnOnlyOneSideAsStructural() throws IOException {
        Path reference = reference("account,plant,value\nA1,P1,50.00\nA3,P1,50.00\n");

        Invocation result = reconcile("reconcile",
                "--actual", outputDir.resolve("results.csv").toString(),
                "--reference", reference.toString(),
                "--precision", "2");

        assertEquals(AllocationCli.EXIT_DIFFERENCES, result.status());
        assertTrue(result.out().contains("matched=1"), result.out());
        assertTrue(result.out().contains("missing=1"), result.out());
        assertTrue(result.out().contains("extra=1"), result.out());
    }

    @Test
    void rejectsDuplicateReferenceRowsWhenAsked() throws IOException {
        Path reference = reference("account,plant,value\nA1,P1,25.00\nA1,P1,25.00\n");

        Invocation result = reconcile("reconcile",
                "--actual", outputDir.resolve("results.csv").toString(),
                "--reference", reference.toString(),
                "--precision", "2",
                "--reject-reference-duplicates");

        assertEquals(1, result.status());
        assertTrue(result.err().contains("duplicate coordinate"), result.err());
    }

    @Test
    void aggregatesDuplicateReferenceRowsByDefault() throws IOException {
        Path reference = reference("account,plant,value\nA1,P1,25.00\nA1,P1,25.00\nA2,P1,50.00\n");

        Invocation result = reconcile("reconcile",
                "--actual", outputDir.resolve("results.csv").toString(),
                "--reference", reference.toString(),
                "--precision", "2");

        assertEquals(0, result.status(), result.out());
        assertTrue(result.out().contains("duplicate_rows=1"), result.out());
    }

    @Test
    void mismatchInDimensionsIsRejected() throws IOException {
        Path reference = reference("account,region,value\nA1,R1,50.00\n");

        Invocation result = reconcile("reconcile",
                "--actual", outputDir.resolve("results.csv").toString(),
                "--reference", reference.toString(),
                "--precision", "2");

        assertEquals(1, result.status());
        assertTrue(result.err().contains("ALLOC_001"), result.err());
    }

    @Test
    void missingArgumentsFailWithUsage() {
        Invocation result = reconcile("reconcile", "--actual", "a.csv");

        assertEquals(2, result.status());
        assertTrue(result.err().contains("usage: reconcile"), result.err());
    }

    @Test
    void unknownOptionFailsWithUsage() {
        Invocation result = reconcile("reconcile", "--nope");

        assertEquals(2, result.status());
        assertTrue(result.err().contains("unknown option"), result.err());
    }
}
