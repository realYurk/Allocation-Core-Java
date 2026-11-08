package io.allocation.http;

import io.allocation.kernel.service.InMemoryRunRepository;
import io.allocation.kernel.service.RunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The contract over real HTTP, in a real application context, on a real port.
 *
 * <p>This is not a repeat of {@code AllocationControllerTest}. MockMvc exercises the controller and the
 * advice but skips everything between them and the socket: message converter selection, content
 * negotiation, how a rejected body becomes a response, whether the advice is registered at all when the
 * application never scans the starter's package. Those are exactly the parts that differ between "works in
 * the test" and "works in the host", and the only way to see them is to serve a real request.
 *
 * <p>The application class lives in this package on purpose: its component scan then finds nothing, so
 * every bean under test arrived through {@code AutoConfiguration.imports}. If the starter were relying on
 * being scanned, this test would fail — which is the point.
 */
@SpringBootTest(classes = AllocationHttpTest.HttpApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AllocationHttpTest {

    private static final String RULE = """
            {
              "schema": "allocation-ir/2.0",
              "rounding_policy": "LARGEST_REMAINDER_IDENTITY",
              "name": "http test",
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

    private static final Path HOME = createHome();

    @SpringBootApplication
    static class HttpApplication {
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("allocation.rules-directory", () -> HOME.toString());
        registry.add("allocation.working-directory", () -> HOME.resolve("work").toString());
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RunRepository repository;

    private static Path createHome() {
        try {
            Path home = Files.createTempDirectory("allocation-http-");
            Files.writeString(home.resolve("rule.json"), RULE, StandardCharsets.UTF_8);
            Files.writeString(home.resolve("amounts.csv"), "plant,account,amount\nP1,SRC,100.00\n",
                    StandardCharsets.UTF_8);
            Files.writeString(home.resolve("drivers.csv"), "plant,account,weight\nP1,A1,1\nP1,A2,1\n",
                    StandardCharsets.UTF_8);
            return home;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void theWholeContractWorksOverARealSocket() {
        ResponseEntity<Map> preview = rest.postForEntity("/allocation/preview", Map.of(
                "ruleCode", "rule",
                "explain", true,
                "rows", java.util.List.of(Map.of(
                        "coord", Map.of("plant", "P1", "account", "SRC"),
                        "amount", "100.00"))), Map.class);

        assertEquals(HttpStatus.OK, preview.getStatusCode());
        assertEquals(200, preview.getBody().get("code"));
        Map<String, Object> data = (Map<String, Object>) preview.getBody().get("data");
        java.util.List<Map<String, Object>> lines = (java.util.List<Map<String, Object>>) data.get("lines");
        assertEquals(2, lines.size());
        assertEquals("50.00", lines.get(0).get("amount"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) data.get("conservation")).get("balanced"));

        ResponseEntity<Map> submitted = rest.postForEntity("/allocation/runs", Map.of(
                "ruleCode", "rule",
                "source", Map.of("type", "csv", "path", HOME.toString()),
                "idempotencyKey", "http-1",
                "options", Map.of("workers", 2)), Map.class);

        assertEquals(HttpStatus.ACCEPTED, submitted.getStatusCode());
        assertEquals(202, submitted.getBody().get("code"));
        String runId = (String) ((Map<String, Object>) submitted.getBody().get("data")).get("runId");
        assertNotNull(runId);

        awaitPublished(runId);

        ResponseEntity<Map> result = rest.getForEntity("/allocation/runs/{id}/result?page=1&size=10", Map.class,
                runId);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        Map<String, Object> resultData = (Map<String, Object>) result.getBody().get("data");
        assertEquals(2, resultData.get("total"));
        assertEquals("PUBLISHED", resultData.get("status"));
    }

    @Test
    void anOversizePageIsRefusedByTheServingContainer() {
        ResponseEntity<Map> response = rest.getForEntity("/allocation/runs/{id}/result?size=999999", Map.class,
                "01ANY");

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals("ALLOC_007", response.getBody().get("errorCode"));
        assertNotNull(response.getBody().get("traceId"));
    }

    @Test
    void anUnknownRunIsABadRequestWithAStableCode() {
        ResponseEntity<Map> response = rest.getForEntity("/allocation/runs/{id}", Map.class, "01MISSING");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("ALLOC_001", response.getBody().get("errorCode"));
    }

    @Test
    void cancellingAFinishedRunIsAnAnswerNotAnError() {
        ResponseEntity<Map> submitted = rest.postForEntity("/allocation/runs", Map.of(
                "ruleCode", "rule",
                "source", Map.of("type", "csv", "path", HOME.toString())), Map.class);
        String runId = (String) ((Map<String, Object>) submitted.getBody().get("data")).get("runId");
        awaitTerminal(runId);

        // A finished run cannot be stopped; the answer is a body, not an error. Stopping a live one is
        // covered deterministically at the service layer, where the run can be parked mid-flight.
        ResponseEntity<Map> cancelled = rest.postForEntity("/allocation/runs/{id}/cancel", null, Map.class, runId);
        assertEquals(HttpStatus.OK, cancelled.getStatusCode());
        assertEquals(Boolean.FALSE, ((Map<String, Object>) cancelled.getBody().get("data")).get("accepted"));
    }

    @Test
    void storageDefaultsToMemoryWhenTheApplicationHasNoDataSource() {
        // Not a formality: it is the auto-configuration's one branching decision, and a host that meant to
        // configure a database but lost the dependency would otherwise find out from an empty table.
        assertInstanceOf(InMemoryRunRepository.class, repository);
    }

    private void awaitPublished(String runId) {
        Map<String, Object> last = awaitTerminal(runId);
        assertEquals("PUBLISHED", last.get("status"));
    }

    private Map<String, Object> awaitTerminal(String runId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        Map<String, Object> data = Map.of();
        while (System.nanoTime() < deadline) {
            ResponseEntity<Map> status = rest.getForEntity("/allocation/runs/{id}", Map.class, runId);
            assertEquals(HttpStatus.OK, status.getStatusCode());
            data = (Map<String, Object>) status.getBody().get("data");
            String state = (String) data.get("status");
            if ("PUBLISHED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state)) {
                return data;
            }
            sleepBriefly();
        }
        throw new AssertionError("run " + runId + " did not finish, last state " + data.get("status"));
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
