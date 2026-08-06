package space.br1440.platform.tracing.autoconfigure.bootstrap;

import java.util.Objects;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class TracingBootstrapSettingsRegistrar {

    public static void register(ConfigurableListableBeanFactory beanFactory,
                                TracingBootstrapSettings settings) {
        Objects.requireNonNull(beanFactory, "beanFactory");
        Objects.requireNonNull(settings, "settings");

        if (beanFactory.containsSingleton(TracingBootstrapPropertyNames.SETTINGS_BEAN_NAME)) {
            throw new IllegalStateException("TracingBootstrapSettings singleton is already registered");
        }

        beanFactory.registerSingleton(TracingBootstrapPropertyNames.SETTINGS_BEAN_NAME, settings);
    }
}
