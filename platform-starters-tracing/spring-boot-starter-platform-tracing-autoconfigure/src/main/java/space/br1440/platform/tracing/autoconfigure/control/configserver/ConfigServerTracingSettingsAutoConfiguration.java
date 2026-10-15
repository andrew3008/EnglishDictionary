package space.br1440.platform.tracing.autoconfigure.control.configserver;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.ConfigurableEnvironment;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannelCondition;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsWriter;

@AutoConfiguration(afterName = ConfigServerTracingSettingsAutoConfiguration.SETTINGS_WRITER_AUTOCONFIGURATION)
@ConditionalOnClass(name = ConfigServerClasspathGuardAutoConfiguration.ENVIRONMENT_CHANGE_EVENT)
@Conditional(TracingControlChannelCondition.OnConfigServer.class)
public class ConfigServerTracingSettingsAutoConfiguration {

    static final String SETTINGS_WRITER_AUTOCONFIGURATION =
            "space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxControlAutoConfiguration";

    @Bean
    ConfigServerSettingsManifestBuilder configServerSettingsManifestBuilder(ConfigurableEnvironment environment) {
        return new ConfigServerSettingsManifestBuilder(environment);
    }

    @Bean
    ConfigServerTracingSettingsSynchronizer configServerTracingSettingsSynchronizer(ConfigServerSettingsManifestBuilder manifestBuilder,
                                                                                    TracingSettingsWriter settingsWriter) {
        return new ConfigServerTracingSettingsSynchronizer(manifestBuilder, settingsWriter);
    }

    @Bean
    ConfigServerTracingSettingsAdapter configServerTracingSettingsAdapter(ConfigServerTracingSettingsSynchronizer synchronizer) {
        return new ConfigServerTracingSettingsAdapter(synchronizer);
    }

    @Bean
    ConfigServerInitialDelivery configServerInitialDelivery(ConfigServerTracingSettingsSynchronizer synchronizer) {
        return new ConfigServerInitialDelivery(synchronizer);
    }
}
