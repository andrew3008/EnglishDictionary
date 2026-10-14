package space.br1440.platform.tracing.autoconfigure.control.configserver;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.util.ClassUtils;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapPropertyNames;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannelCondition;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;

@AutoConfiguration
@Conditional(TracingControlChannelCondition.OnConfigServer.class)
public class ConfigServerClasspathGuardAutoConfiguration {

    static final String ENVIRONMENT_CHANGE_EVENT = "org.springframework.cloud.context.environment.EnvironmentChangeEvent";
    static final String DELIVERY_UNAVAILABLE = "CONFIG_SERVER_DELIVERY_UNAVAILABLE";

    @Bean
    static BeanFactoryPostProcessor configServerClasspathGuard() {
        return beanFactory -> {
            String[] beanNamesForType = beanFactory.getBeanNamesForType(
                    ConfigServerInitialDelivery.class,
                    true,
                    false
            );

            if (beanNamesForType.length > 0) {
                return;
            }

            boolean cloudContextPresent = ClassUtils.isPresent(
                    ENVIRONMENT_CHANGE_EVENT,
                    beanFactory.getBeanClassLoader()
            );

            String reason = cloudContextPresent
                    ? "CONFIG_SERVER delivery auto-configuration did not register a delivery bean (excluded or conditions not matched)."
                    : "CONFIG_SERVER control channel requires " + ENVIRONMENT_CHANGE_EVENT + " (spring-cloud-context) on the classpath.";

            throw new TracingConfigurationGuardException("""
                    %s: %s \
                    Action: add org.springframework.cloud:spring-cloud-context to the application, \
                    or set %s to NONE or ACTUATOR. \
                    Run with --debug to see the condition evaluation report.\
                    """.formatted(DELIVERY_UNAVAILABLE, reason, TracingBootstrapPropertyNames.CONTROL_CHANNEL));
        };
    }
}
