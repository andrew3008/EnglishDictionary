package space.br1440.platform.tracing.autoconfigure.configuration;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.configuration.actuator.TracingActuatorAutoConfiguration;

/**
 * Early production wiring for {@link TracingConfigurationAuthorityGuard}.
 * <p>
 * Runs as a {@link BeanFactoryPostProcessor} before ordinary tracing beans are
 * instantiated, so illegal authority/posture combinations fail closed before
 * Actuator private-control clients, readiness probes, or delivery adapters can
 * activate inconsistently.
 * <p>
 * Registered first in {@code AutoConfiguration.imports} and ordered before
 * {@link TracingCoreAutoConfiguration} and {@link TracingActuatorAutoConfiguration}.
 */
@AutoConfiguration
@AutoConfigureBefore({
        TracingCoreAutoConfiguration.class,
        TracingActuatorAutoConfiguration.class
})
public class TracingConfigurationAuthorityAutoConfiguration {

    public static final String GUARD_BEAN_NAME = "tracingConfigurationAuthorityGuard";

    @Bean
    public static BeanFactoryPostProcessor tracingConfigurationAuthorityGuardProcessor() {
        return new AuthorityGuardBeanFactoryPostProcessor();
    }

    static final class AuthorityGuardBeanFactoryPostProcessor
            implements BeanFactoryPostProcessor, EnvironmentAware {

        private Environment environment;

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
                throws BeansException {
            TracingConfigurationAuthorityGuard guard =
                    TracingConfigurationAuthorityGuard.from(environment).validate();
            if (!beanFactory.containsSingleton(GUARD_BEAN_NAME)) {
                beanFactory.registerSingleton(GUARD_BEAN_NAME, guard);
            }
        }
    }
}
