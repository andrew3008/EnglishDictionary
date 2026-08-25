package space.br1440.platform.tracing.autoconfigure.control.authority;

import jakarta.annotation.Nonnull;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettingsRegistrar;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettingsResolver;
import space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerTracingSettingsAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PrivateJmxControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsControlAutoConfiguration;

@AutoConfiguration
@AutoConfigureBefore({
        TracingRuntimeAutoConfiguration.class,
        PrivateJmxControlAutoConfiguration.class,
        TracingSettingsControlAutoConfiguration.class,
        ConfigServerTracingSettingsAutoConfiguration.class
})
public class TracingConfigurationAuthorityAutoConfiguration {

    public static final String GUARD_BEAN_NAME = "tracingConfigurationAuthorityGuard";

    @Bean
    public static BeanFactoryPostProcessor tracingConfigurationAuthorityGuardProcessor() {
        return new AuthorityGuardBeanFactoryPostProcessor();
    }

    static final class AuthorityGuardBeanFactoryPostProcessor implements BeanFactoryPostProcessor, EnvironmentAware {

        private Environment environment;

        @Override
        public void setEnvironment(@Nonnull Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanFactory(@Nonnull ConfigurableListableBeanFactory beanFactory) throws BeansException {
            TracingBootstrapSettings settings;
            try {
                settings = TracingBootstrapSettingsResolver.resolve(environment);
            } catch (IllegalStateException exception) {
                throw new TracingConfigurationGuardException(exception.getMessage());
            }

            TracingBootstrapSettingsRegistrar.register(beanFactory, settings);

            TracingConfigurationAuthorityGuard guard = new TracingConfigurationAuthorityGuard(settings).validate();
            if (!beanFactory.containsSingleton(GUARD_BEAN_NAME)) {
                beanFactory.registerSingleton(GUARD_BEAN_NAME, guard);
            }
        }
    }
}
