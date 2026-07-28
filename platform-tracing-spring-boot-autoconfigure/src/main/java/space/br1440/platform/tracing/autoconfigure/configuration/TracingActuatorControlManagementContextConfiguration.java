package space.br1440.platform.tracing.autoconfigure.configuration;

import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestController;

/**
 * Management-context wiring локальной typed HTTP-поверхности управления.
 *
 * <p>До P6 класс намеренно не включён в production management imports.
 */
@ManagementContextConfiguration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@ConditionalOnClass(RestController.class)
final class TracingActuatorControlManagementContextConfiguration {

    @Bean
    @ConditionalOnBean(SpringActuatorTracingPolicyAdapter.class)
    TracingActuatorControlController tracingActuatorControlController(
            SpringActuatorTracingPolicyAdapter adapter) {
        return new TracingActuatorControlController(adapter);
    }
}
