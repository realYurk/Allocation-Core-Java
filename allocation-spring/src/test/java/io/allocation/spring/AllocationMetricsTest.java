package io.allocation.spring;

import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.service.AllocationListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the meters say after a run, driven by events rather than by a Spring context.
 *
 * <p>Every assertion here is a claim an operator would make out loud — "one run, sixteen pools, thirty-six
 * rows, and it is no longer in flight" — so a wrong meter is a wrong sentence, which is the only kind of
 * wrong that gets noticed.
 */
class AllocationMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AllocationMetrics metrics = new AllocationMetrics(registry);

    @Test
    void aPublishedRunIsCountedOnceAndLeavesNothingInFlight() {
        metrics.on(new AllocationListener.Submitted("R1", "probe", false, 1_000));
        assertEquals(1.0, registry.get(AllocationMetrics.IN_FLIGHT).gauge().value());

        metrics.on(new AllocationListener.Started("R1", 1_250));
        metrics.on(new AllocationListener.Published("R1", 10_000, 10_000, 16, 36, 2_000));

        assertEquals(1.0, counter(AllocationMetrics.SUBMITTED, "rule", "probe").count());
        assertEquals(1.0, registry.get(AllocationMetrics.STARTED).counter().count());
        assertEquals(1.0, counter(AllocationMetrics.FINISHED, "outcome", "published").count());
        assertEquals(0.0, registry.get(AllocationMetrics.IN_FLIGHT).gauge().value());

        DistributionSummary pools = registry.get(AllocationMetrics.POOLS).summary();
        assertEquals(1, pools.count());
        assertEquals(16.0, pools.totalAmount());
        DistributionSummary rows = registry.get(AllocationMetrics.ROWS).summary();
        assertEquals(36.0, rows.totalAmount());
        assertEquals(10_000.0, registry.get(AllocationMetrics.AMOUNT).summary().totalAmount());

        Timer queue = timer(AllocationMetrics.QUEUE_TIME, "probe");
        assertEquals(1, queue.count());
        assertEquals(250.0, queue.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 1.0);
        Timer run = timer(AllocationMetrics.RUN_TIME, "probe");
        assertEquals(1, run.count());
        assertEquals(750.0, run.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 1.0);
    }

    @Test
    void aReplayIsCountedButIsNotASecondRun() {
        metrics.on(new AllocationListener.Submitted("R1", "probe", false, 1_000));
        metrics.on(new AllocationListener.Submitted("R1", "probe", true, 1_100));
        metrics.on(new AllocationListener.Started("R1", 1_200));
        metrics.on(new AllocationListener.Published("R1", 1, 1, 1, 1, 1_900));

        assertEquals(2.0, counter(AllocationMetrics.SUBMITTED, "rule", "probe").count());
        assertEquals(1.0, counter(AllocationMetrics.REPLAYED, "rule", "probe").count());
        assertEquals(1.0, counter(AllocationMetrics.FINISHED, "outcome", "published").count());
        assertEquals(0.0, registry.get(AllocationMetrics.IN_FLIGHT).gauge().value());

        // The replay must not overwrite the original submission time with its own: 200ms is the queue
        // time of the first request, and a retry is not a second wait.
        Timer queue = timer(AllocationMetrics.QUEUE_TIME, "probe");
        assertEquals(1, queue.count());
        assertEquals(200.0, queue.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 1.0);
    }

    @Test
    void aFailureIsCountedByBothOutcomeAndCode() {
        metrics.on(new AllocationListener.Submitted("R1", "probe", false, 1_000));
        metrics.on(new AllocationListener.Started("R1", 1_000));
        metrics.on(new AllocationListener.Failed("R1", ErrorCode.AMOUNT_OUT_OF_RANGE, "bad literal", 1_500));

        assertEquals(1.0, counter(AllocationMetrics.FINISHED, "outcome", "failed").count());
        assertEquals(1.0, counter(AllocationMetrics.FAILURE, "code", "ALLOC_002").count());
        assertEquals(0.0, registry.get(AllocationMetrics.IN_FLIGHT).gauge().value());
        assertEquals(0.0, registry.get(AllocationMetrics.POOLS).summary().count(),
                "a failed run has no pool count to report, and zero samples is how that is said");
    }

    @Test
    void aCancellationIsNotAFailure() {
        metrics.on(new AllocationListener.Submitted("R1", "probe", false, 1_000));
        metrics.on(new AllocationListener.Started("R1", 1_000));
        metrics.on(new AllocationListener.Cancelled("R1", 3_000));

        assertEquals(1.0, counter(AllocationMetrics.FINISHED, "outcome", "cancelled").count());
        assertTrue(registry.find(AllocationMetrics.FAILURE).counters().isEmpty(),
                "a cancel must not add a failure series; that is what makes the two states separable");
    }

    @Test
    void aRunThatNeverStartedHasNoDuration() {
        // Reachable when the rule cannot be loaded: the run fails before execution begins.
        metrics.on(new AllocationListener.Submitted("R1", "probe", false, 1_000));
        metrics.on(new AllocationListener.Failed("R1", ErrorCode.RULE_INVALID, "no such rule", 1_100));

        assertTrue(registry.find(AllocationMetrics.RUN_TIME).timers().isEmpty(),
                "a missing start is a missing measurement, not a zero-millisecond run");
        assertEquals(0.0, registry.get(AllocationMetrics.IN_FLIGHT).gauge().value());
    }

    @Test
    void aClockThatStepsBackwardsDoesNotRecordANegativeDuration() {
        metrics.on(new AllocationListener.Submitted("R1", "probe", false, 5_000));
        metrics.on(new AllocationListener.Started("R1", 5_000));
        metrics.on(new AllocationListener.Published("R1", 1, 1, 1, 1, 4_000));

        assertNull(registry.find(AllocationMetrics.RUN_TIME).timer(),
                "a negative sample poisons the histogram for as long as the process lives");
    }

    @Test
    void theInFlightGaugeNeverGoesBelowZero() {
        // Defensive: an out-of-order terminal event must not leave a gauge that reads -1 for ever.
        metrics.on(new AllocationListener.Cancelled("GHOST", 1_000));
        assertEquals(0.0, registry.get(AllocationMetrics.IN_FLIGHT).gauge().value());
    }

    @Test
    void aRuleCodeIsNeverUsedAsAMeterName() {
        // Rule codes come from files, and a file name can be anything; the tag value is the safe place.
        metrics.on(new AllocationListener.Submitted("R1", "cost allocation/2026", false, 1_000));

        assertEquals(1.0, counter(AllocationMetrics.SUBMITTED, "rule", "cost allocation/2026").count());
        assertNotNull(registry.find(AllocationMetrics.SUBMITTED).counter());
    }

    @Test
    void theTerminalCountersExistBeforeAnyRunSoADashboardHasSomethingToDraw() {
        for (String outcome : List.of("published", "failed", "cancelled")) {
            assertNotNull(counter(AllocationMetrics.FINISHED, "outcome", outcome));
        }
        assertNotNull(registry.get(AllocationMetrics.STARTED).counter());
        assertNotNull(registry.get(AllocationMetrics.IN_FLIGHT).gauge());
    }

    private Counter counter(String name, String tagKey, String tagValue) {
        Counter found = registry.get(name).tag(tagKey, tagValue).counter();
        assertNotNull(found, name + "{" + tagKey + "=" + tagValue + "} was never registered");
        return found;
    }

    private Timer timer(String name, String rule) {
        return registry.get(name).tag("rule", rule).timer();
    }
}
