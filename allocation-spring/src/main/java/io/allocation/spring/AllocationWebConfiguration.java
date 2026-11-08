package io.allocation.spring;

import io.allocation.kernel.service.AllocationTemplate;
import io.allocation.spring.web.AllocationController;
import io.allocation.spring.web.AllocationExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * The REST binding, registered the way an autoconfigured starter has to register it.
 *
 * <p>Neither class below is found by component scanning, and that is deliberate: a starter's package is
 * not on the host's scan path, so any class that assumes it is exists only in the author's test. Both are
 * declared as beans here instead, which also makes the whole surface replaceable — a host that wants an
 * extra audit endpoint subclasses {@link AllocationController} and declares its own bean of that type.
 *
 * <p>The web layer is conditional on being a servlet web application, so the same jar in a batch process
 * wires the engine and no controllers. A batch process that instantiates a {@code @RequestMapping} it
 * will never serve is a small thing, but it is the kind of small thing that ends up in a startup log for
 * years.
 */
@AutoConfiguration(after = AllocationAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(AllocationProperties.class)
public class AllocationWebConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AllocationController allocationController(AllocationTemplate template,
                                                     AllocationProperties properties) {
        return new AllocationController(template, properties);
    }

    /**
     * Registering an advice as a bean is enough: Spring's exception resolver collects
     * {@code @ControllerAdvice} beans from the context, whether they were scanned or declared.
     */
    @Bean
    @ConditionalOnMissingBean
    public AllocationExceptionHandler allocationExceptionHandler() {
        return new AllocationExceptionHandler();
    }
}
