package io.allocation.example;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The example application, all the way through.
 *
 * <p>Three things are being asserted, and only one of them is about arithmetic. First, an application whose
 * base package is {@code io.allocation.example} finds the engine at all — no component scan reaches
 * {@code io.allocation.spring}, so every bean under test came from {@code AutoConfiguration.imports}.
 * Second, the shipped sample rule and its data actually allocate, which is what a reader runs first. Third,
 * the run reconciles against the checked-in reference, which turns the example into a golden-file test: if a
 * change to the engine moves one of those 36 amounts, this fails instead of quietly printing new numbers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExampleApplicationTest {

    /**
     * A copy of {@code data/} in a temporary directory, so a run never writes into the checkout and the
     * tests can run in parallel with a developer's own {@code spring-boot:run}.
     */
    private static final Path SANDBOX = copyOfData();

    @DynamicPropertySource
    static void sandbox(DynamicPropertyRegistry registry) {
        registry.add("allocation.working-directory", () -> SANDBOX.resolve("work").toString());
        registry.add("allocation.rules-directory", () -> SANDBOX.resolve("data/rules").toString());
        registry.add("example.reference", () -> SANDBOX.resolve("data/reference/results.csv").toString());
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ExampleRun state;

    @Test
    void theDemoRunPublishesWithTheSampleTotals() {
        String runId = state.runId();
        assertNotNull(runId, "the demo runner must have submitted a run at startup");

        ResponseEntity<Map> status = rest.getForEntity("/allocation/runs/{id}", Map.class, runId);
        assertEquals(HttpStatus.OK, status.getStatusCode());
        Map<String, Object> body = data(status);
        assertEquals("PUBLISHED", body.get("status"));

        Map<String, Object> progress = (Map<String, Object>) body.get("progress");
        assertEquals(16, ((Number) progress.get("pools")).intValue(),
                "4 pools in the first pass, 12 in the second");

        ResponseEntity<Map> page = rest.getForEntity(
                "/allocation/runs/{id}/result?page=1&size=10", Map.class, runId);
        Map<String, Object> result = data(page);
        assertEquals(36, ((Number) result.get("total")).intValue());
        assertEquals(10, ((List<?>) result.get("lines")).size());

        Map<String, Object> conservation = (Map<String, Object>) result.get("conservation");
        assertEquals("410000.00", conservation.get("input"), "the DAG total, counted once");
        assertEquals("410000.00", conservation.get("output"));
        assertEquals(Boolean.TRUE, conservation.get("balanced"));
    }

    @Test
    void theShippedReferenceStillMatches() {
        ResponseEntity<Map> reconciled = rest.postForEntity("/allocation/runs/{id}/reconcile",
                Map.of("reference", Map.of("type", "csv",
                        "path", SANDBOX.resolve("data/reference/results.csv").toString())),
                Map.class, state.runId());

        assertEquals(HttpStatus.OK, reconciled.getStatusCode(),
                String.valueOf(reconciled.getBody()));
        Map<String, Object> body = data(reconciled);
        assertEquals("MATCH", body.get("status"));
        assertEquals(36, ((Number) body.get("matched")).intValue());
        assertEquals(0, ((Number) body.get("amountDiffered")).intValue());
    }

    @Test
    void resubmittingTheSameKeyIsAReplayOfTheSameRun() {
        ResponseEntity<Map> submitted = rest.postForEntity("/allocation/runs", Map.of(
                "ruleCode", "cost-allocation",
                "source", Map.of("type", "csv",
                        "path", SANDBOX.resolve("data/rules").toString()),
                "idempotencyKey", "example-demo"), Map.class);

        // 202, because a submission is queued: the same answer for a first submission and a replay.
        assertEquals(HttpStatus.ACCEPTED, submitted.getStatusCode());
        Map<String, Object> body = data(submitted, 202);
        assertEquals(state.runId(), body.get("runId"), "same key, same run");
        assertEquals(Boolean.TRUE, body.get("idempotentReplay"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theStarterPublishesRunMetricsToTheActuatorEndpoint() {
        // Actuator is on this module's classpath and nothing else was configured, so this asserts the
        // whole optional path at once: the auto-configuration found the MeterRegistry, the listener was
        // composed into the engine, and the demo run's publish is visible where an operator looks.
        ResponseEntity<Map> response = rest.getForEntity(
                "/actuator/metrics/allocation.run.finished?tag=outcome:published", Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), String.valueOf(response.getBody()));

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        List<Map<String, Object>> measurements = (List<Map<String, Object>>) body.get("measurements");
        double count = measurements.stream()
                .filter(m -> "COUNT".equals(m.get("statistic")))
                .mapToDouble(m -> ((Number) m.get("value")).doubleValue())
                .findFirst()
                .orElse(0.0);
        assertEquals(1.0, count, "the demo run is the only published run in this context");
    }

    // ------------------------------------------------------------------ helpers
    private static Map<String, Object> data(ResponseEntity<Map> response) {
        return data(response, 200);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map> response, int code) {
        Map<String, Object> envelope = response.getBody();
        assertNotNull(envelope, "empty body");
        assertEquals(code, ((Number) envelope.get("code")).intValue(), "envelope: " + envelope);
        return (Map<String, Object>) envelope.get("data");
    }

    /** Copies the module's {@code data/} directory into a fresh temporary directory. */
    private static Path copyOfData() {
        try {
            Path root = Files.createTempDirectory("allocation-example-");
            Path source = Path.of("data");
            assertTrue(Files.isDirectory(source), "the example's data directory must be next to the pom");
            try (Stream<Path> files = Files.walk(source)) {
                for (Path file : files.toList()) {
                    Path relative = source.relativize(file);
                    Path target = root.resolve("data").resolve(relative);
                    if (Files.isDirectory(file)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(file, target);
                    }
                }
            }
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
