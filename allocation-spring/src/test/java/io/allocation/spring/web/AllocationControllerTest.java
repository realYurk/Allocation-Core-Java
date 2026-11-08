package io.allocation.spring.web;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.service.AllocationTemplate;
import io.allocation.kernel.service.DefaultAllocationTemplate;
import io.allocation.kernel.service.DirectoryRuleResolver;
import io.allocation.kernel.service.Explain;
import io.allocation.kernel.service.InMemoryRunRepository;
import io.allocation.kernel.service.Preview;
import io.allocation.kernel.service.RunRepository;
import io.allocation.kernel.service.RunResult;
import io.allocation.kernel.service.RunView;
import io.allocation.spring.AllocationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The REST surface, exercised against a real engine rather than a mock of one.
 *
 * <p>A mock would prove that the controller calls the template, which is the part that cannot be wrong.
 * What can be wrong is the mapping — a money literal losing its scale, a status name, an error code
 * becoming the wrong HTTP status — and none of that is visible unless the engine actually runs.
 */
class AllocationControllerTest {

    private static final String RULE = """
            {
              "schema": "allocation-ir/2.0",
              "rounding_policy": "LARGEST_REMAINDER_IDENTITY",
              "name": "web test",
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

    @TempDir
    Path directory;

    private InMemoryRunRepository repository;
    private DefaultAllocationTemplate template;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(directory.resolve("rule.json"), RULE, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("amounts.csv"), "plant,account,amount\nP1,SRC,100.00\n",
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("drivers.csv"), "plant,account,weight\nP1,A1,1\nP1,A2,1\n",
                StandardCharsets.UTF_8);
        repository = new InMemoryRunRepository();
        template = new DefaultAllocationTemplate(repository, new DirectoryRuleResolver(directory),
                directory.resolve("work"));
        mvc = mvc(template);
    }

    @AfterEach
    void tearDown() {
        template.close();
    }

    private MockMvc mvc(AllocationTemplate engine) {
        AllocationProperties properties = new AllocationProperties();
        properties.setWorkingDirectory(directory.resolve("work"));
        properties.setRulesDirectory(directory);
        return MockMvcBuilders
                .standaloneSetup(new AllocationController(engine, properties))
                .setControllerAdvice(new AllocationExceptionHandler())
                .build();
    }

    private String submitBody(String key) {
        return """
                {"ruleCode":"rule","source":{"type":"csv","path":%s},"idempotencyKey":%s,
                 "options":{"workers":2,"retainLines":true}}
                """.formatted(json(directory.toString()), key == null ? "null" : json(key));
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---------------------------------------------------------------- preview

    @Test
    void previewReturnsMoneyAsDecimalStrings() throws Exception {
        mvc.perform(post("/allocation/preview").contentType(MediaType.APPLICATION_JSON).content("""
                        {"ruleCode":"rule","explain":true,"rows":[
                          {"coord":{"plant":"P1","account":"SRC"},"amount":"100.00"}]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.errorCode").doesNotExist())
                .andExpect(jsonPath("$.data.lines[0].amount").value("50.00"))
                .andExpect(jsonPath("$.data.lines[1].amount").value("50.00"))
                .andExpect(jsonPath("$.data.lines[0].coord.account").value("A1"))
                .andExpect(jsonPath("$.data.conservation.input").value("100.00"))
                .andExpect(jsonPath("$.data.conservation.output").value("100.00"))
                .andExpect(jsonPath("$.data.conservation.balanced").value(true))
                .andExpect(jsonPath("$.data.lines[0].trace.weight").value(1_000_000))
                .andExpect(jsonPath("$.data.lines[0].trace.denominator").value(2_000_000));
    }

    @Test
    void previewAcceptsTrailingZerosWithoutLosingThem() throws Exception {
        // 1.5000 is 1.50 at a two-decimal rule: dropping a zero never changes an amount, so it must
        // not become an ALLOC_002 either.
        mvc.perform(post("/allocation/preview").contentType(MediaType.APPLICATION_JSON).content("""
                        {"ruleCode":"rule","rows":[
                          {"coord":{"plant":"P1","account":"SRC"},"amount":"100.0000"}]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conservation.input").value("100.00"));
    }

    @Test
    void previewRejectsAnAmountWithTooManyDecimals() throws Exception {
        mvc.perform(post("/allocation/preview").contentType(MediaType.APPLICATION_JSON).content("""
                        {"ruleCode":"rule","rows":[
                          {"coord":{"plant":"P1","account":"SRC"},"amount":"100.001"}]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ALLOC_002"));
    }

    @Test
    void previewRejectsAnUnreadableBody() throws Exception {
        mvc.perform(post("/allocation/preview").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ALLOC_001"));
    }

    // ---------------------------------------------------------------- runs

    @Test
    void submitIsAcceptedThenPollableThenReadable() throws Exception {
        String body = mvc.perform(post("/allocation/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("web-1")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(202))
                .andExpect(jsonPath("$.data.idempotentReplay").value(false))
                .andReturn().getResponse().getContentAsString();
        String runId = field(body, "\"runId\":\"");

        template.await(runId, Duration.ofSeconds(10));

        mvc.perform(get("/allocation/runs/{runId}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.progress.pools").value(1));

        mvc.perform(get("/allocation/runs/{runId}/result", runId).param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.lines.length()").value(1))
                .andExpect(jsonPath("$.data.conservation.balanced").value(true));
    }

    @Test
    void resubmittingTheSameKeyIsReplayedNotRecomputed() throws Exception {
        String first = mvc.perform(post("/allocation/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("web-2")))
                .andReturn().getResponse().getContentAsString();
        template.await(field(first, "\"runId\":\""), Duration.ofSeconds(10));

        mvc.perform(post("/allocation/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("web-2")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.runId").value(field(first, "\"runId\":\"")))
                .andExpect(jsonPath("$.data.idempotentReplay").value(true))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
    }

    @Test
    void submitWithAnUnknownRuleIsBadRequest() throws Exception {
        mvc.perform(post("/allocation/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleCode\":\"nope\",\"source\":{\"type\":\"csv\",\"path\":\"/tmp\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ALLOC_001"));
    }

    @Test
    void submitWithAnUnknownSourceTypeIsBadRequest() throws Exception {
        mvc.perform(post("/allocation/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleCode\":\"rule\",\"source\":{\"type\":\"parquet\",\"path\":\"/tmp\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ALLOC_001"))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("csv or memory")));
    }

    @Test
    void resultOfAnUnpublishedRunIsAConflict() throws Exception {
        repository.insert(new RunRepository.RunRecord("run-pending", "rule", "sha", null, "req", 2,
                RunRepository.Status.PENDING, 0L, 0L, 0, 0L, null, null, System.currentTimeMillis(), null));

        mvc.perform(get("/allocation/runs/{runId}/result", "run-pending"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ALLOC_006"));
    }

    @Test
    void anOversizePageIsRefused() throws Exception {
        mvc.perform(get("/allocation/runs/{runId}/result", "any").param("size", "999999"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("ALLOC_007"));
    }

    @Test
    void statusOfAnUnknownRunIsBadRequest() throws Exception {
        mvc.perform(get("/allocation/runs/{runId}", "missing"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ALLOC_001"));
    }

    @Test
    void explainCarriesTheDecisionChain() throws Exception {
        String body = mvc.perform(post("/allocation/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(null)))
                .andReturn().getResponse().getContentAsString();
        String runId = field(body, "\"runId\":\"");
        template.await(runId, Duration.ofSeconds(10));

        mvc.perform(get("/allocation/runs/{runId}/explain", runId).param("plant", "P1").param("account", "A1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps[0].stageId").value("st1"))
                .andExpect(jsonPath("$.data.steps[0].ruleId").value("r1"))
                .andExpect(jsonPath("$.data.steps[0].poolAmount").value("100.00"))
                .andExpect(jsonPath("$.data.steps[0].weight").value(1_000_000))
                .andExpect(jsonPath("$.data.steps[0].denominator").value(2_000_000))
                .andExpect(jsonPath("$.data.steps[0].allocatedAmount").value("50.00"))
                .andExpect(jsonPath("$.data.ruleSha256").isNotEmpty());
    }

    @Test
    void explainWithoutACoordinateIsBadRequest() throws Exception {
        mvc.perform(get("/allocation/runs/{runId}/explain", "any"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ALLOC_001"));
    }

    // ---------------------------------------------------------------- reconcile

    @Test
    void reconcileReportsThePublishedAnswerAgainstAReference() throws Exception {
        String body = mvc.perform(post("/allocation/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(null)))
                .andReturn().getResponse().getContentAsString();
        String runId = field(body, "\"runId\":\"");
        template.await(runId, Duration.ofSeconds(10));

        Path reference = directory.resolve("reference.csv");
        Files.writeString(reference, "plant,account,amount\nP1,A1,50.00\nP1,A2,49.99\n", StandardCharsets.UTF_8);

        mvc.perform(post("/allocation/runs/{runId}/reconcile", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":{\"type\":\"csv\",\"path\":" + json(reference.toString())
                                + "},\"tolerance\":\"0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DIFFERENCES"))
                .andExpect(jsonPath("$.data.matched").value(1))
                .andExpect(jsonPath("$.data.amountDiffered").value(1))
                .andExpect(jsonPath("$.data.referenceSha256").isNotEmpty())
                .andExpect(jsonPath("$.data.differenceFile").value(
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString(runId),
                                org.hamcrest.Matchers.endsWith("differences.csv"))));
    }

    @Test
    void reconcileRejectsAnUnknownDuplicatePolicy() throws Exception {
        mvc.perform(post("/allocation/runs/{runId}/reconcile", "any")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":{\"type\":\"csv\",\"path\":\"/tmp/r.csv\"},"
                                + "\"duplicatePolicy\":\"MERGE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ALLOC_001"))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("AGGREGATE")));
    }

    // ---------------------------------------------------------------- cancel

    @Test
    void cancellingAFinishedRunIsAnAnswerNotAnError() throws Exception {
        String body = mvc.perform(post("/allocation/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(null)))
                .andReturn().getResponse().getContentAsString();
        String runId = field(body, "\"runId\":\"");
        template.await(runId, Duration.ofSeconds(10));

        mvc.perform(post("/allocation/runs/{runId}/cancel", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(false))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
    }

    @Test
    void cancellingAnUnknownRunIsBadRequest() throws Exception {
        mvc.perform(post("/allocation/runs/{runId}/cancel", "missing"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ALLOC_001"));
    }

    // ---------------------------------------------------------------- error context

    @Test
    void aFailureCarriesItsCoordinates() throws Exception {
        AllocationTemplate failing = new DelegatingTemplate(template) {
            @Override
            public Preview preview(io.allocation.kernel.service.PreviewCommand command) {
                throw new AllocationException(ErrorCode.NO_ELIGIBLE_DRIVERS, "no candidate in pool")
                        .withContext("st1", "r1", "P1|TOTAL");
            }
        };

        mvc(failing).perform(post("/allocation/preview").contentType(MediaType.APPLICATION_JSON).content("""
                        {"ruleCode":"rule","rows":[{"coord":{"plant":"P1","account":"SRC"},"amount":"1.00"}]}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("ALLOC_004"))
                .andExpect(jsonPath("$.data.stageId").value("st1"))
                .andExpect(jsonPath("$.data.allocationId").value("r1"))
                .andExpect(jsonPath("$.data.poolKey").value("P1|TOTAL"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void everyErrorCodeHasAnHttpStatus() {
        for (ErrorCode code : ErrorCode.values()) {
            assertTrue(AllocationExceptionHandler.statusOf(code).isError(), code + " is not an error status");
        }
        assertEquals(12, ErrorCode.values().length, "docs/API.md §3 must list every code");
    }

    // ---------------------------------------------------------------- helpers

    /** Extracts a value from the JSON body that was just produced, without a second JSON library. */
    private static String field(String body, String prefix) {
        int start = body.indexOf(prefix);
        if (start < 0) {
            throw new AssertionError(prefix + " not found in " + body);
        }
        start += prefix.length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    /**
     * Delegating {@link AllocationTemplate}, so a test can make exactly one method fail.
     *
     * <p>Every method is forwarded, which is the point: the test asserts one behaviour and cannot
     * accidentally assert the stub instead.
     */
    private static class DelegatingTemplate implements AllocationTemplate {
        private final AllocationTemplate delegate;

        DelegatingTemplate(AllocationTemplate delegate) {
            this.delegate = delegate;
        }

        @Override
        public Preview preview(io.allocation.kernel.service.PreviewCommand command) {
            return delegate.preview(command);
        }

        @Override
        public SubmitResult submit(io.allocation.kernel.service.RunCommand command) {
            return delegate.submit(command);
        }

        @Override
        public RunView status(String runId) {
            return delegate.status(runId);
        }

        @Override
        public RunResult await(String runId, Duration timeout) {
            return delegate.await(runId, timeout);
        }

        @Override
        public RunResult result(String runId, int page, int size) {
            return delegate.result(runId, page, size);
        }

        @Override
        public Explain explain(String runId, Map<String, String> coord) {
            return delegate.explain(runId, coord);
        }

        @Override
        public ReconcileOutcome reconcile(io.allocation.kernel.service.ReconcileCommand command) {
            return delegate.reconcile(command);
        }

        @Override
        public boolean cancel(String runId) {
            return delegate.cancel(runId);
        }
    }
}
