package space.br1440.platform.tracing.autoconfigure.control.channel;

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
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerTracingSettingsAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsControlAutoConfiguration;

@AutoConfiguration
@AutoConfigureBefore({
        TracingRuntimeAutoConfiguration.class,
        PlatformJmxControlAutoConfiguration.class,
        TracingSettingsControlAutoConfiguration.class,
        ConfigServerTracingSettingsAutoConfiguration.class
})
public class TracingControlChannelAutoConfiguration {

    @Bean
    public static BeanFactoryPostProcessor tracingControlChannelBootstrapProcessor() {
        return new ControlChannelBootstrapProcessor();
    }

    static final class ControlChannelBootstrapProcessor implements BeanFactoryPostProcessor, EnvironmentAware {

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
        }
    }
}
