package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

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
 * <strong>Both</strong> conditions must be true simultaneously (AND semantics):
 * <ul>
 *   <li>{@code platform.tracing.configuration.authority = ACTUATOR}</li>
 *   <li>{@code platform.environment = LOCAL}</li>
 * </ul>
 * Repeating {@code @ConditionalOnProperty} on a class does NOT give AND semantics
 * in Spring Boot: only the last annotation is evaluated by the condition infrastructure.
 * The correct approach is {@link AllNestedConditions} with both conditions as inner
 * nested classes — Spring evaluates each independently and requires all to pass.
 * Physical absence of the controller is guaranteed when either condition is false.
 * <p>
 * Absolute path composition: the management base-path is injected via
 * {@code @Value} and applied via {@code WebMvcConfigurer.configurePathMatch()}.
 * The controller itself carries only the relative {@code /tracingControl} prefix.
 */
@ManagementContextConfiguration
@Conditional(TracingControlManagementContextConfiguration.OnActuatorAndLocalEnvironment.class)
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

    /**
     * AND conjunction of two {@code @ConditionalOnProperty} conditions.
     * <p>
     * {@link AllNestedConditions} evaluates every nested {@code @Condition}
     * class independently; the outer condition passes only when ALL nested
     * conditions pass. This is semantically equivalent to:
     * <pre>
     *   platform.tracing.configuration.authority == ACTUATOR
     *   AND
     *   platform.environment == LOCAL
     * </pre>
     * {@code ConfigurationPhase.REGISTER_BEAN} is required because this
     * configuration registers beans (not component-scan infrastructure).
     */
    static final class OnActuatorAndLocalEnvironment extends AllNestedConditions {

        OnActuatorAndLocalEnvironment() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(
                name = "platform.tracing.configuration.authority",
                havingValue = "ACTUATOR",
                matchIfMissing = false)
        static class OnActuatorAuthority {}

        @ConditionalOnProperty(
                name = "platform.environment",
                havingValue = "LOCAL",
                matchIfMissing = false)
        static class OnLocalEnvironment {}
    }
}
