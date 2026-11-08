package io.allocation.spring;

import io.allocation.kernel.service.AllocationListener;
import io.allocation.kernel.service.AllocationTemplate;
import io.allocation.kernel.service.DefaultAllocationTemplate;
import io.allocation.kernel.service.DirectoryRuleResolver;
import io.allocation.kernel.service.InMemoryRunRepository;
import io.allocation.kernel.service.JdbcRunRepository;
import io.allocation.kernel.service.RuleResolver;
import io.allocation.kernel.service.RunRepository;
import io.allocation.kernel.service.TenantProvider;
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
 *
 * <h2>Tenancy</h2>
 * A host that is multi-tenant declares one {@link TenantProvider} bean and gets a filtered repository
 * without changing a single call site. A host that is not declares nothing: the column defaults to an
 * empty string and the behaviour is exactly what it was before tenants existed. There is no
 * "multi-tenant mode" switch, because a mode is a branch that only one side of ever gets tested.
 */
@AutoConfiguration
@ConditionalOnClass(AllocationTemplate.class)
@EnableConfigurationProperties(AllocationProperties.class)
public class AllocationAutoConfiguration {

    /**
     * Persistence for runs: the host's database if it has one, memory if it does not.
     *
     * <p>A {@link TenantProvider} bean is picked up if there is one, and it is the only place in the
     * starter where a duplicate is an error rather than something to be composed. Listeners can be many
     * because each one observes; a tenant cannot, because the filter is a decision about whose data this
     * is. Silently choosing one of two would scope a company's runs to another company, and a stack trace
     * at startup is the cheap version of that bug.
     *
     * <p>Both repositories take the provider, not just the JDBC one: a host develops against memory and
     * deploys against a database, so a filter present in only one of them is a leak that no pre-deployment
     * test would show.
     */
    @Bean
    @ConditionalOnMissingBean
    public RunRepository allocationRunRepository(ObjectProvider<DataSource> dataSources,
                                                 ObjectProvider<TenantProvider> tenants) {
        DataSource dataSource = dataSources.getIfUnique();
        TenantProvider tenant = tenants.getIfAvailable(TenantProvider::none);
        return dataSource == null ? new InMemoryRunRepository(tenant)
                : new JdbcRunRepository(dataSource, tenant);
    }

    /** Rule documents, read from {@code allocation.rules-directory}. */
    @Bean
    @ConditionalOnMissingBean
    public RuleResolver allocationRuleResolver(AllocationProperties properties) {
        return new DirectoryRuleResolver(properties.getRulesDirectory());
    }

    /**
     * The engine facade the controllers (and any host code) call.
     *
     * <p>If the host declared an {@link AllocationAuthorizer}, the template is wrapped in
     * {@link AuthorizingAllocationTemplate} so every endpoint passes through it. If it did not, the raw
     * template is returned: a decorator that checks a permit-all rule is a stack frame on the hot path
     * bought with nothing.
     *
     * <p>Listeners are composed rather than required to be unique. There is one reason: the starter may
     * install its own (metrics), and a host that also declares one would otherwise fail to start with
     * "expected single matching bean" — a wiring error caused by a feature the host never asked for. With
     * no listener at all the no-op instance is passed, so the engine keeps its identity fast path.
     */
    @Bean
    @ConditionalOnMissingBean
    public AllocationTemplate allocationTemplate(RunRepository repository, RuleResolver rules,
                                                 AllocationProperties properties,
                                                 ObjectProvider<AllocationAuthorizer> authorizer,
                                                 ObjectProvider<AllocationListener> listeners) {
        AllocationTemplate engine = new DefaultAllocationTemplate(repository, rules,
                properties.getWorkingDirectory(), properties.getQueueCapacity(),
                AllocationListener.allOf(listeners.orderedStream().toList()));
        AllocationAuthorizer guard = authorizer.getIfAvailable();
        return guard == null ? engine : new AuthorizingAllocationTemplate(engine, guard);
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
