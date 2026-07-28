package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

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
 * simultaneously (AND semantics). {@code @ConditionalOnProperty} is
 * {@code @Repeatable(ConditionalOnProperties.class)}; {@code OnPropertyCondition}
 * iterates all repeated annotations and returns {@code noMatch} if any single
 * one fails. Repeating the annotation is therefore the correct and sufficient
 * mechanism — no {@code AllNestedConditions} wrapper is needed.
 * <p>
 * Conditions:
 * <ul>
 *   <li>{@code platform.tracing.configuration.authority = ACTUATOR}</li>
 *   <li>{@code platform.environment = LOCAL}</li>
 * </ul>
 * Physical absence of the controller is guaranteed when either condition is false.
 * <p>
 * Absolute path composition: the management base-path is injected via
 * {@code @Value} and applied via {@code WebMvcConfigurer.configurePathMatch()}.
 * The controller itself carries only the relative {@code /tracingControl} prefix.
 */
@ManagementContextConfiguration
@ConditionalOnProperty(
        name = "platform.tracing.configuration.authority",
        havingValue = "ACTUATOR",
        matchIfMissing = false)
@ConditionalOnProperty(
        name = "platform.environment",
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
     * Registers an absolute path prefix for {@link TracingControlMvcController}
     * so that its relative {@code /tracingControl} mapping resolves to
     * {@code {managementBasePath}/tracingControl} on the management server.
     * <p>
     * Using {@code WebMvcConfigurer.configurePathMatch()} with
     * {@code addPathPrefix} is the standard Spring MVC approach for
     * per-controller absolute-path injection in a child context.
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
