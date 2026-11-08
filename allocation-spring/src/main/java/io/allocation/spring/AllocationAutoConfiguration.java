package io.allocation.spring;

import io.allocation.kernel.service.AllocationTemplate;
import io.allocation.kernel.service.DefaultAllocationTemplate;
import io.allocation.kernel.service.DirectoryRuleResolver;
import io.allocation.kernel.service.InMemoryRunRepository;
import io.allocation.kernel.service.JdbcRunRepository;
import io.allocation.kernel.service.RuleResolver;
import io.allocation.kernel.service.RunRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;

/**
 * Wiring for the engine, and nothing else.
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean}, which is what makes this usable inside a framework
 * like RuoYi: the host can replace the repository with its own MyBatis mapper, or the template with a
 * decorator that adds authorisation, without fighting the starter for ownership.
 *
 * <h2>Storage</h2>
 * If the application already has a {@link DataSource}, runs are persisted with {@link JdbcRunRepository}
 * against the DDL in {@code ddl/}. If it does not, the in-memory repository is used — which is correct
 * for a test or a single-node tool, and is not an audit trail. The choice is made once, visibly, rather
 * than by silently falling back when a query fails.
 *
 * <p>The engine itself is not conditional on anything but its own presence: it has no runtime
 * dependency beyond the JDK, so there is nothing here that can be missing.
 */
@AutoConfiguration
@ConditionalOnClass(AllocationTemplate.class)
@EnableConfigurationProperties(AllocationProperties.class)
public class AllocationAutoConfiguration {

    /** Persistence for runs: the host's database if it has one, memory if it does not. */
    @Bean
    @ConditionalOnMissingBean
    public RunRepository allocationRunRepository(ObjectProvider<DataSource> dataSources) {
        DataSource dataSource = dataSources.getIfUnique();
        return dataSource == null ? new InMemoryRunRepository() : new JdbcRunRepository(dataSource);
    }

    /** Rule documents, read from {@code allocation.rules-directory}. */
    @Bean
    @ConditionalOnMissingBean
    public RuleResolver allocationRuleResolver(AllocationProperties properties) {
        return new DirectoryRuleResolver(properties.getRulesDirectory());
    }

    /** The engine facade the controllers (and any host code) call. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public AllocationTemplate allocationTemplate(RunRepository repository, RuleResolver rules,
                                                 AllocationProperties properties) {
        return new DefaultAllocationTemplate(repository, rules, properties.getWorkingDirectory(),
                properties.getQueueCapacity());
    }

    /**
     * Optional startup sweep for runs left behind by a dead process.
     *
     * <p>The cutoff is this JVM's own start time, which is only a proof of abandonment when one process
     * owns the database — hence the off-by-default property. When it is on, a run that was interrupted
     * becomes {@code FAILED} with {@code ALLOC_012} during startup instead of saying {@code RUNNING} for
     * ever.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "allocation", name = "recover-interrupted-runs", havingValue = "true")
    public InterruptedRunSweep allocationInterruptedRunSweep(RunRepository repository) {
        return new InterruptedRunSweep(repository, ManagementFactory.getRuntimeMXBean().getStartTime());
    }
}
