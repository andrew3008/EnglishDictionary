package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link TracingControlMvcController} exclusively in the management
 * WebApplicationContext.
 * <p>
 * Both conditions must be satisfied simultaneously (AND semantics via two
 * @ConditionalOnProperty annotations). The controller is physically absent if either:
 * - authority != ACTUATOR, or
 * - platform.environment != LOCAL
 * <p>
 * This is a hard registration guard, separate from the startup validate() call.
 * Fail-fast from TracingConfigurationAuthorityGuard provides an additional
 * runtime check; this conditional provides physical absence.
 */
@ManagementContextConfiguration
@ConditionalOnProperty(
        name = "platform.tracing.configuration.authority",
        havingValue = "ACTUATOR",
        matchIfMissing = false
)
@ConditionalOnProperty(
        name = "platform.environment",
        havingValue = "LOCAL",
        matchIfMissing = false
)
public class TracingControlManagementContextConfiguration {

    @Bean
    public TracingControlMvcController tracingControlMvcController(
            SpringActuatorTracingPolicyAdapter adapter) {
        return new TracingControlMvcController(adapter);
    }
}
