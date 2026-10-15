package space.br1440.platform.tracing.autoconfigure.control.channel;

import java.util.List;
import jakarta.annotation.Nonnull;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettingsRegistrar;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettingsResolver;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingPropertyAdmission;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerClasspathGuardAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.configserver.ConfigServerTracingSettingsAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxReadAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsControlAutoConfiguration;

@AutoConfiguration
@AutoConfigureBefore({
        TracingRuntimeAutoConfiguration.class,
        PlatformJmxReadAutoConfiguration.class,
        PlatformJmxControlAutoConfiguration.class,
        TracingSettingsControlAutoConfiguration.class,
        ConfigServerTracingSettingsAutoConfiguration.class,
        ConfigServerClasspathGuardAutoConfiguration.class
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
            if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
                throw new TracingConfigurationGuardException("Tracing property admission requires a ConfigurableEnvironment");
            }

            TracingBootstrapSettings settings;
            try {
                settings = TracingBootstrapSettingsResolver.resolve(configurableEnvironment);
            } catch (IllegalStateException exception) {
                throw new TracingConfigurationGuardException(exception.getMessage());
            }

            List<TracingPropertyAdmission.Violation> violations = TracingPropertyAdmission.check(
                    configurableEnvironment,
                    settings.controlChannel()
            );

            if (!violations.isEmpty()) {
                String describe = TracingPropertyAdmission.describe(settings.controlChannel(), violations);
                throw new TracingConfigurationGuardException(describe);
            }

            TracingBootstrapSettingsRegistrar.register(beanFactory, settings);
        }
    }
}
