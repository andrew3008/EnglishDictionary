package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link TracingControlMvcController} in the management
 * WebApplicationContext only.
 * <p>
 * Discovered via:
 * META-INF/spring/
 *   org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration.imports
 * <p>
 * Both @ConditionalOnProperty conditions must be true (AND semantics):
 *   - platform.tracing.configuration.authority = ACTUATOR
 *   - platform.environment = LOCAL
 * Physical absence is guaranteed when either condition is false.
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

    @Bean
    public TracingControlMvcController tracingControlMvcController(
            SpringActuatorTracingPolicyAdapter adapter) {
        return new TracingControlMvcController(adapter, "/actuator");
    }
}
