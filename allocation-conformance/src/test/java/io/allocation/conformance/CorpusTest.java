package io.allocation.conformance;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.exec.AllocationRunner;
import io.allocation.kernel.exec.Executor;
import io.allocation.kernel.exec.RunSummary;
import io.allocation.kernel.json.Json;
import io.allocation.kernel.json.JsonFields;
import io.allocation.kernel.json.JsonValue;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The acceptance gate.
 *
 * <p>Every scenario in the frozen corpus is replayed through the Java engine and compared byte for byte
 * against the reference implementation's output. Nothing else counts: not coverage, not review, not
 * "looks right". If this test is green, the engine computes what the proven implementation computes.
 *
 * <p>Scenarios marked {@code FAIL} assert that the engine rejects the input. The reason may differ in
 * wording — the reference has no error taxonomy — but the outcome must not: a document that produces
 * books in one engine and an error in the other is exactly the kind of divergence this corpus exists to
 * catch.
 */
class CorpusTest {

    private static final List<String> ARTEFACTS = List.of(
            "results.csv", "ledger.csv", "source_to_pool.csv", "reconciliation.csv", "lineage_links.csv");

    private static final Path CORPUS = Path.of("src", "test", "resources", "corpus");

    @TestFactory
    Stream<DynamicTest> replaysEveryScenario() throws IOException {
        assertTrue(Files.isDirectory(CORPUS), "corpus directory missing: " + CORPUS.toAbsolutePath());
        List<Path> scenarios;
        try (Stream<Path> listing = Files.list(CORPUS)) {
            scenarios = listing.filter(Files::isDirectory).sorted().toList();
        }
        assertTrue(scenarios.size() >= 30, "expected a substantial corpus, found " + scenarios.size());
        return scenarios.stream().map(dir -> DynamicTest.dynamicTest(dir.getFileName().toString(),
                () -> replay(dir)));
    }

    private void replay(Path scenario) throws IOException {
        String expectedStatus = expectedStatus(scenario);
        Path rule = scenario.resolve("rule.json");
        Path output = Files.createTempDirectory("allocation-corpus-");
        Files.delete(output); // the runner requires a path that does not exist yet

        if ("FAIL".equals(expectedStatus)) {
            AllocationException failure = assertThrows(AllocationException.class,
                    () -> AllocationRunner.run(rule, output),
                    "scenario must be rejected: " + scenario.getFileName());
            assertTrue(!Files.exists(output), "a rejected run must not leave output behind");
            assertTrue(failure.code() != null, "rejection must carry an error code");
            return;
        }

        RunSummary summary;
        try {
            summary = AllocationRunner.run(rule, output);
        } finally {
            // nothing to clean up on success; the temp directory is asserted below
        }
        assertEquals("PASS", summary.status(), "run did not pass: " + scenario.getFileName());
        assertEquals(summary.inputAmount(), summary.outputAmount(),
                "input and output totals must match: " + scenario.getFileName());

        Path golden = scenario.resolve(".golden");
        for (String artefact : ARTEFACTS) {
            Path expected = golden.resolve(artefact);
            Path actual = output.resolve(artefact);
            if (!Files.exists(expected)) {
                continue;
            }
            assertTrue(Files.exists(actual), "missing artefact " + artefact + " for " + scenario.getFileName());
            byte[] want = Files.readAllBytes(expected);
            byte[] got = Files.readAllBytes(actual);
            if (!Arrays.equals(want, got)) {
                throw new AssertionError("artefact differs: " + artefact + " in " + scenario.getFileName()
                        + "\n" + firstDifference(want, got));
            }
            assertArrayEquals(want, got, artefact + " must be byte-identical");
        }
    }

    private static String expectedStatus(Path scenario) throws IOException {
        String text = Files.readString(scenario.resolve("expect.json"), StandardCharsets.UTF_8);
        JsonValue root = Json.parse(text);
        JsonFields fields = JsonFields.of(root, scenario.getFileName().toString());
        String status = fields.requireString("status");
        fields.optString("note");
        fields.done();
        return status;
    }

    /** Produces a short, readable description of where two text files diverge. */
    private static String firstDifference(byte[] expected, byte[] actual) {
        String want = new String(expected, StandardCharsets.UTF_8);
        String got = new String(actual, StandardCharsets.UTF_8);
        String[] wantLines = want.split("\n", -1);
        String[] gotLines = got.split("\n", -1);
        List<String> report = new ArrayList<>();
        int limit = Math.min(wantLines.length, gotLines.length);
        for (int i = 0; i < limit; i++) {
            if (!wantLines[i].equals(gotLines[i])) {
                report.add("first differing line " + (i + 1));
                report.add("  expected: " + abbreviate(wantLines[i]));
                report.add("  actual  : " + abbreviate(gotLines[i]));
                break;
            }
        }
        if (report.isEmpty()) {
            report.add("line counts differ: expected " + wantLines.length + ", actual " + gotLines.length);
        }
        return String.join("\n", report);
    }

    private static String abbreviate(String line) {
        return line.length() <= 220 ? line : line.substring(0, 220) + "...";
    }

    /** Guards the corpus itself: every scenario must declare what it expects. */
    @TestFactory
    Stream<DynamicTest> everyScenarioIsWellFormed() throws IOException {
        List<Path> scenarios;
        try (Stream<Path> listing = Files.list(CORPUS)) {
            scenarios = listing.filter(Files::isDirectory).sorted().toList();
        }
        return scenarios.stream().map(dir -> DynamicTest.dynamicTest("well-formed: " + dir.getFileName(),
                () -> {
                    assertTrue(Files.exists(dir.resolve("rule.json")),
                            "missing rule.json in " + dir.getFileName());
                    String status = expectedStatus(dir);
                    assertTrue(status.equals("PASS") || status.equals("FAIL"),
                            "unexpected expected status " + status);
                    if ("PASS".equals(status)) {
                        assertTrue(Files.isDirectory(dir.resolve(".golden")),
                                "PASS scenario must be frozen: " + dir.getFileName());
                        for (String artefact : ARTEFACTS) {
                            assertTrue(Files.exists(dir.resolve(".golden").resolve(artefact)),
                                    "missing frozen " + artefact + " in " + dir.getFileName());
                        }
                    }
                }));
    }

    /** Parallelism must not change a single byte. */
    @TestFactory
    Stream<DynamicTest> workerCountDoesNotChangeOutput() throws IOException {
        List<Path> scenarios;
        try (Stream<Path> listing = Files.list(CORPUS)) {
            scenarios = listing.filter(Files::isDirectory).sorted().toList();
        }
        return scenarios.stream()
                .filter(dir -> {
                    try {
                        return "PASS".equals(expectedStatus(dir));
                    } catch (IOException e) {
                        return false;
                    }
                })
                .map(dir -> DynamicTest.dynamicTest("deterministic: " + dir.getFileName(), () -> {
                    byte[] sequential = runAndRead(dir.resolve("rule.json"), "results.csv", 1);
                    byte[] parallel = runAndRead(dir.resolve("rule.json"), "results.csv", 8);
                    assertArrayEquals(sequential, parallel,
                            "worker count changed the result ordering in " + dir.getFileName());
                }));
    }

    private static byte[] runAndRead(Path rule, String artefact, int workers) throws IOException {
        Path output = Files.createTempDirectory("allocation-determinism-");
        Files.delete(output);
        AllocationRunner.run(rule, output, new Executor.Options(workers, false));
        return Files.readAllBytes(output.resolve(artefact));
    }
}
