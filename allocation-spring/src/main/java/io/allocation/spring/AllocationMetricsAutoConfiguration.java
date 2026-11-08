package io.allocation.spring;

import io.allocation.kernel.service.AllocationListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link AllocationMetrics} when, and only when, the application has somewhere to publish to.
 *
 * <h2>Why a separate auto-configuration</h2>
 * This method's signature names {@link MeterRegistry}. Putting it inside {@link AllocationAutoConfiguration}
 * would make the engine's own auto-configuration depend on micrometer being on the classpath — the class
 * would fail to load in an application that has no actuator, which is the ordinary case for a batch job. A
 * separate class means the engine keeps its "no runtime dependency beyond the JDK" property and the
 * optional feature pays its own way.
 *
 * <h2>Why the registry is asked for, not conditioned on</h2>
 * The obvious spelling is {@code @ConditionalOnBean(MeterRegistry.class)}. It is wrong here, and the
 * failure is silent: that condition only sees beans registered by auto-configurations processed
 * <em>before</em> this one, and which of them creates the registry depends on which export backend the
 * host happens to have (actuator's plain {@code MetricsAutoConfiguration} for one, {@code
 * SimpleMetricsExportAutoConfiguration}, Prometheus, and so on — each with its own position in the queue).
 * Get the ordering wrong and the starter quietly reports nothing, which is exactly what happened the first
 * time: the application had a registry, the dashboard had a hole, and no log line said why.
 *
 * <p>Asking through {@link ObjectProvider} has no ordering requirement at all: by the time any bean is
 * instantiated, every definition is registered, so the registry is created on demand if it exists and
 * absent if it does not. The bean returns the shared no-op listener in that case, which {@link
 * AllocationListener#allOf} drops, so an application without actuator ends up exactly where it started.
 *
 * <h2>What a host can do about it</h2>
 * Declare its own {@link AllocationMetrics} bean (this backs off), set {@code allocation.metrics.enabled}
 * to {@code false} (this backs off), or leave micrometer off the classpath (this never loads). All three
 * are intentional: a starter that cannot be turned off is a starter that gets forked.
 */
@AutoConfiguration(after = AllocationAutoConfiguration.class)
@ConditionalOnClass(AllocationListener.class)
@ConditionalOnProperty(prefix = "allocation.metrics", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AllocationMetricsAutoConfiguration {

    /**
     * The metrics listener, or the no-op one when there is no {@link MeterRegistry} to publish to.
     *
     * <p>Declared as {@link AllocationListener} rather than {@link AllocationMetrics} precisely so the
     * "nothing to publish to" case can be answered with an object instead of a null bean.
     */
    @Bean
    @ConditionalOnMissingBean(AllocationMetrics.class)
    public AllocationListener allocationMetrics(ObjectProvider<MeterRegistry> registries) {
        MeterRegistry registry = registries.getIfAvailable();
        return registry == null ? AllocationListener.noop() : new AllocationMetrics(registry);
    }
}
