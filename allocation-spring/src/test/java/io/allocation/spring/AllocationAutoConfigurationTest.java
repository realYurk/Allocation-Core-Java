package io.allocation.spring;

import io.allocation.kernel.service.AllocationTemplate;
import io.allocation.kernel.service.InMemoryRunRepository;
import io.allocation.kernel.service.RunRepository;
import io.allocation.spring.web.AllocationController;
import io.allocation.spring.web.AllocationExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What the starter actually puts in a context.
 *
 * <p>A starter's failures are quietly boring: a bean that never got registered, a property that silently
 * defaulted, a controller that only exists when the author's test scans its package. Each of those is
 * invisible in a unit test of the classes themselves, so each is asserted here through the same
 * auto-configuration a host would load.
 */
class AllocationAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AllocationAutoConfiguration.class));

    @Test
    void wiresTheEngineWithoutADataSource(@TempDir Path directory) {
        runner.withPropertyValues("allocation.working-directory=" + directory,
                        "allocation.rules-directory=" + directory,
                        "allocation.workers=4",
                        "allocation.max-rows-in-memory=1000",
                        "allocation.queue-capacity=3")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(AllocationTemplate.class));
                    assertInstanceOf(InMemoryRunRepository.class, context.getBean(RunRepository.class));

                    AllocationProperties properties = context.getBean(AllocationProperties.class);
                    assertEquals(4, properties.getWorkers());
                    assertEquals(1_000L, properties.getMaxRowsInMemory());
                    assertEquals(3, properties.getQueueCapacity());
                });
    }

    @Test
    void propertiesHaveDefaultsThatNeedNoConfiguration() {
        runner.run(context -> {
            AllocationProperties properties = context.getBean(AllocationProperties.class);
            assertEquals(1, properties.getWorkers());
            assertEquals(16, properties.getQueueCapacity());
            assertEquals("allocation-data", properties.getWorkingDirectory().toString());
            assertEquals("allocation-rules", properties.getRulesDirectory().toString());
            assertEquals(io.allocation.kernel.exec.Executor.Options.DEFAULT_MAX_ROWS_IN_MEMORY,
                    properties.getMaxRowsInMemory());
        });
    }

    @Test
    void aHostBeanReplacesTheEngineBean() {
        AllocationTemplate mine = new DelegatingNoop();

        runner.withBean(AllocationTemplate.class, () -> mine)
                .run(context -> assertEquals(mine, context.getBean(AllocationTemplate.class)));
    }

    @Test
    void anAuthorizerBeanIsWrappedAroundTheEngine(@TempDir Path directory) {
        runner.withBean(AllocationAuthorizer.class, () -> (action, runId) -> {
                })
                .withPropertyValues("allocation.working-directory=" + directory,
                        "allocation.rules-directory=" + directory)
                .run(context -> assertInstanceOf(AuthorizingAllocationTemplate.class,
                        context.getBean(AllocationTemplate.class)));
    }

    @Test
    void withoutAnAuthorizerTheEngineIsNotWrapped(@TempDir Path directory) {
        // A decorator that checks a permit-all rule on every call is a frame bought with nothing, so the
        // wrapper must appear only when a host asked for it.
        runner.withPropertyValues("allocation.working-directory=" + directory,
                        "allocation.rules-directory=" + directory)
                .run(context -> assertInstanceOf(io.allocation.kernel.service.DefaultAllocationTemplate.class,
                        context.getBean(AllocationTemplate.class)));
    }

    @Test
    void aListenerBeanReceivesTheLifecycleOfARun(@TempDir Path directory) throws java.io.IOException {
        // A real run through the bean the context built, because "the listener was accepted as a bean" and
        // "the listener is called" are different claims and only the second one is useful.
        java.nio.file.Files.writeString(directory.resolve("probe.json"), """
                {"schema":"allocation-ir/2.0","rounding_policy":"LARGEST_REMAINDER_IDENTITY",
                 "name":"probe","precision":2,"dimensions":["plant","account"],
                 "immutable_dimensions":[],"hierarchies":{},
                 "datasets":{"amounts":{"amounts":"probe-amounts.csv"},
                             "drivers":{"drivers":"probe-drivers.csv"}},
                 "stages":[{"id":"st1","inputs":[{"from":"amounts","share":"1"}],
                   "source":{"grouping":{"plant":"AS_IS","account":"TOTAL"},"filters":{}},
                   "target":{"plant":{"mode":"SOURCE"},
                             "account":{"mode":"INCLUDE","include":["A1"]}},
                   "conservation_keys":["plant"],
                   "allocations":[{"id":"r1","share":"1","driver_dataset":"drivers",
                                   "weight_field":"weight"}]}]}
                """, java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(directory.resolve("probe-amounts.csv"),
                "plant,account,amount\nP1,SRC,10.00\n", java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(directory.resolve("probe-drivers.csv"),
                "plant,account,weight\nP1,A1,1\n", java.nio.charset.StandardCharsets.UTF_8);

        java.util.List<io.allocation.kernel.service.AllocationListener.Event> events =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        runner.withBean(io.allocation.kernel.service.AllocationListener.class, () -> events::add)
                .withPropertyValues("allocation.working-directory=" + directory,
                        "allocation.rules-directory=" + directory)
                .run(context -> {
                    AllocationTemplate template = context.getBean(AllocationTemplate.class);
                    AllocationTemplate.SubmitResult submitted = template.submit(
                            io.allocation.kernel.service.RunCommand.builder()
                                    .ruleCode("probe")
                                    .source(new io.allocation.kernel.service.AmountSource.Csv(directory))
                                    .build());
                    template.await(submitted.runId(), java.time.Duration.ofSeconds(10));

                    assertEquals(java.util.List.of("Submitted", "Started", "Published"),
                            events.stream().map(e -> e.getClass().getSimpleName()).toList());
                });
    }

    @Test
    void theWebLayerIsRegisteredInAServletApplication(@TempDir Path directory) {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AllocationAutoConfiguration.class,
                        AllocationWebConfiguration.class))
                .withPropertyValues("allocation.working-directory=" + directory,
                        "allocation.rules-directory=" + directory)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(AllocationController.class));
                    assertNotNull(context.getBean(AllocationExceptionHandler.class));
                });
    }

    @Test
    void aPlainApplicationStillGetsTheEngineButNoControllers(@TempDir Path directory) {
        runner.withConfiguration(AutoConfigurations.of(AllocationWebConfiguration.class))
                .withPropertyValues("allocation.working-directory=" + directory,
                        "allocation.rules-directory=" + directory)
                .run(context -> {
                    assertNotNull(context.getBean(AllocationTemplate.class));
                    assertEquals(0, context.getBeansOfType(AllocationController.class).size());
                });
    }

    @Test
    void optionallyAbandonsRunsLeftByAPreviousProcess(@TempDir Path directory) {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        repository.insert(new io.allocation.kernel.service.RunRepository.RunRecord("01STRANDED", "rule", "sha",
                null, "req", 2, io.allocation.kernel.service.RunRepository.Status.RUNNING, 0L, 0L, 0, 0L,
                null, null, 1L, null));

        runner.withBean(RunRepository.class, () -> repository)
                .withPropertyValues("allocation.working-directory=" + directory,
                        "allocation.rules-directory=" + directory,
                        "allocation.recover-interrupted-runs=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    io.allocation.kernel.service.RunRepository.RunRecord record =
                            repository.find("01STRANDED").orElseThrow();
                    assertEquals(io.allocation.kernel.service.RunRepository.Status.FAILED, record.status());
                    assertEquals("ALLOC_012", record.failureCode());
                });
    }

    @Test
    void leavesStrandedRunsAloneWhenTheSweepIsNotEnabled(@TempDir Path directory) {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        repository.insert(new io.allocation.kernel.service.RunRepository.RunRecord("01STRANDED", "rule", "sha",
                null, "req", 2, io.allocation.kernel.service.RunRepository.Status.RUNNING, 0L, 0L, 0, 0L,
                null, null, 1L, null));

        runner.withBean(RunRepository.class, () -> repository)
                .withPropertyValues("allocation.working-directory=" + directory,
                        "allocation.rules-directory=" + directory)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(io.allocation.kernel.service.RunRepository.Status.RUNNING,
                            repository.find("01STRANDED").orElseThrow().status());
                });
    }

    /** Minimal replacement, used only to prove the conditional backs off. */
    private static final class DelegatingNoop implements AllocationTemplate {
        @Override
        public io.allocation.kernel.service.Preview preview(
                io.allocation.kernel.service.PreviewCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SubmitResult submit(io.allocation.kernel.service.RunCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.allocation.kernel.service.RunView status(String runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.allocation.kernel.service.RunResult await(String runId, java.time.Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.allocation.kernel.service.RunResult result(String runId, int page, int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.allocation.kernel.service.Explain explain(String runId,
                                                            java.util.Map<String, String> coord) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReconcileOutcome reconcile(io.allocation.kernel.service.ReconcileCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean cancel(String runId) {
            return false;
        }
    }
}
