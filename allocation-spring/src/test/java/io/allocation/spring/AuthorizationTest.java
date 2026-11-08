package io.allocation.spring;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.service.AllocationTemplate;
import io.allocation.kernel.service.AmountSource;
import io.allocation.kernel.service.Explain;
import io.allocation.kernel.service.Preview;
import io.allocation.kernel.service.PreviewCommand;
import io.allocation.kernel.service.ReconcileCommand;
import io.allocation.kernel.service.RunCommand;
import io.allocation.kernel.service.RunResult;
import io.allocation.kernel.service.RunView;
import io.allocation.spring.web.AllocationController;
import io.allocation.spring.web.AllocationExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The authorisation hook, and the one ordering that matters: check first, work second.
 *
 * <p>A refusal that arrives after the engine has already compiled a rule and created a run is not a
 * refusal, it is a note. So the delegate here records every call and returns nothing useful: after a
 * refusal the record must be empty, which is a stronger statement than "the response said 403".
 */
class AuthorizationTest {

    @TempDir
    Path directory;

    @Test
    void aRefusalReachesTheDelegateZeroTimes() {
        RecordingTemplate delegate = new RecordingTemplate();
        AllocationTemplate guarded = new AuthorizingAllocationTemplate(delegate,
                (action, runId) -> {
                    throw new AllocationException(ErrorCode.FORBIDDEN, "no allocation for you");
                });

        assertThrows(AllocationException.class, () -> guarded.preview(null));
        assertThrows(AllocationException.class, () -> guarded.submit(null));
        assertThrows(AllocationException.class, () -> guarded.status("run-1"));
        assertThrows(AllocationException.class, () -> guarded.result("run-1", 1, 10));
        assertThrows(AllocationException.class, () -> guarded.explain("run-1", Map.of()));
        assertThrows(AllocationException.class, () -> guarded.cancel("run-1"));
        assertThrows(AllocationException.class, () -> guarded.await("run-1", Duration.ofSeconds(1)));
        assertTrue(delegate.calls.isEmpty(), "the engine was touched: " + delegate.calls);
    }

    @Test
    void everyActionIsCheckedWithTheRunItConcerns() {
        List<String> seen = new CopyOnWriteArrayList<>();
        AllocationTemplate guarded = new AuthorizingAllocationTemplate(new RecordingTemplate(),
                (action, runId) -> seen.add(action + "/" + runId));

        guarded.submit(RunCommand.builder().ruleCode("r").source(new AmountSource.Csv(directory)).build());
        guarded.result("run-7", 1, 10);
        guarded.cancel("run-7");
        guarded.reconcile(ReconcileCommand.builder().runId("run-7").reference(directory.resolve("x.csv")).build());

        assertEquals(List.of("SUBMIT/null", "READ/run-7", "CANCEL/run-7", "RECONCILE/run-7"), seen);
    }

    @Test
    void aRefusalOverHttpIsForbiddenAndSaysWhy() throws Exception {
        AllocationProperties properties = new AllocationProperties();
        properties.setWorkingDirectory(directory.resolve("work"));
        properties.setRulesDirectory(directory);
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new AllocationController(new AuthorizingAllocationTemplate(
                        new RecordingTemplate(), (action, runId) -> {
                            throw new AllocationException(ErrorCode.FORBIDDEN, "role allocation:view required");
                        }), properties))
                .setControllerAdvice(new AllocationExceptionHandler())
                .build();

        mvc.perform(post("/allocation/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleCode\":\"rule\",\"rows\":[]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.errorCode").value("ALLOC_010"))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString(
                        "role allocation:view required")));
    }

    @Test
    void aRefusedReadDoesNotRevealWhetherTheRunExists() throws Exception {
        AllocationProperties properties = new AllocationProperties();
        properties.setWorkingDirectory(directory.resolve("work"));
        properties.setRulesDirectory(directory);
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new AllocationController(new AuthorizingAllocationTemplate(
                        new RecordingTemplate(), (action, runId) -> {
                            throw new AllocationException(ErrorCode.FORBIDDEN, "not yours");
                        }), properties))
                .setControllerAdvice(new AllocationExceptionHandler())
                .build();

        // The same answer for a run nobody may read as for a run that does not exist. If authorisation ran
        // after the lookup, an unauthorised caller could enumerate run ids by their status codes.
        mvc.perform(get("/allocation/runs/run-1")).andExpect(status().isForbidden());
        mvc.perform(get("/allocation/runs/does-not-exist")).andExpect(status().isForbidden());
    }

    /** Answers nothing; every method records that it was reached, so a refusal can be proved complete. */
    private static final class RecordingTemplate implements AllocationTemplate {

        private final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public Preview preview(PreviewCommand command) {
            calls.add("preview");
            return null;
        }

        @Override
        public SubmitResult submit(RunCommand command) {
            calls.add("submit");
            return null;
        }

        @Override
        public RunView status(String runId) {
            calls.add("status");
            return null;
        }

        @Override
        public RunResult await(String runId, Duration timeout) {
            calls.add("await");
            return null;
        }

        @Override
        public RunResult result(String runId, int page, int size) {
            calls.add("result");
            return null;
        }

        @Override
        public Explain explain(String runId, Map<String, String> coord) {
            calls.add("explain");
            return null;
        }

        @Override
        public ReconcileOutcome reconcile(ReconcileCommand command) {
            calls.add("reconcile");
            return null;
        }

        @Override
        public boolean cancel(String runId) {
            calls.add("cancel");
            return false;
        }
    }
}
