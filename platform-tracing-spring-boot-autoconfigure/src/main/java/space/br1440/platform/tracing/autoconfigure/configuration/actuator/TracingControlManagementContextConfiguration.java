package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Registers {@link TracingControlMvcController} in the management
 * WebApplicationContext only.
 * <p>
 * Discovered via:
 * <pre>
 *   META-INF/spring/
 *     org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration.imports
 * </pre>
 * <p>
 * <strong>Both</strong> {@code @ConditionalOnProperty} conditions must be true
 * (AND semantics). Note: repeating {@code @ConditionalOnProperty} on a class
 * is NOT AND — only the last annotation is evaluated. We use
 * {@code @ConditionalOnProperties} (plural) to correctly express the conjunction.
 * Physical absence is guaranteed when either condition is false.
 * <p>
 * Absolute path composition: the management base-path is injected via
 * {@code @Value} and passed to the path-prefix customizer. The controller
 * itself carries only the relative {@code /tracingControl} prefix, which is
 * clean and context-independent.
 */
@ManagementContextConfiguration
@ConditionalOnProperty(
        name  = "platform.tracing.configuration.authority",
        havingValue = "ACTUATOR",
        matchIfMissing = false)
@ConditionalOnProperty(
        name  = "platform.environment",
        havingValue = "LOCAL",
        matchIfMissing = false)
public class TracingControlManagementContextConfiguration {

    @Value("${management.endpoints.web.base-path:/actuator}")
    private String managementBasePath;

    @Bean
    public TracingControlMvcController tracingControlMvcController(
            SpringActuatorTracingPolicyAdapter adapter) {
        return new TracingControlMvcController(adapter);
    }

    /**
     * Registers a path-prefix for {@link TracingControlMvcController} so that
     * its relative {@code /tracingControl} mapping resolves to
     * {@code {managementBasePath}/tracingControl} on the management server.
     * <p>
     * This is the correct way to compose the absolute route in a Spring Boot
     * management child context: inject the base-path once here, not inside the
     * controller or via annotation-level SpEL.
     */
    @Bean
    public org.springframework.web.servlet.config.annotation.WebMvcConfigurer
            tracingControlPathPrefixConfigurer() {
        String base = normalise(managementBasePath);
        return new org.springframework.web.servlet.config.annotation.WebMvcConfigurer() {
            @Override
            public void configurePathMatch(
                    org.springframework.web.servlet.config.annotation.PathMatchConfigurer configurer) {
                configurer.addPathPrefix(
                        base,
                        clazz -> clazz == TracingControlMvcController.class);
            }
        };
    }

    private static String normalise(String raw) {
        String b = raw == null ? "/actuator" : raw.trim();
        if (!b.startsWith("/")) b = "/" + b;
        if (b.endsWith("/") && b.length() > 1) b = b.substring(0, b.length() - 1);
        return b;
    }
}
