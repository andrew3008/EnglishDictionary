package space.br1440.platform.metrics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

public class EnvPostProcessor implements EnvironmentPostProcessor {
    private final PropertiesPropertySourceLoader propertySourceLoader;

    public EnvPostProcessor() {
        propertySourceLoader = new PropertiesPropertySourceLoader();
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var resource = new ClassPathResource("default.properties"); // определяем default.properties как локальный ресурс
        PropertySource<?> propertySource = null;
        try {
            propertySource = propertySourceLoader.load("platform-metric", resource).get(0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // прочитанные настройки проставляются в настройки окружения Spring'а
        environment.getPropertySources().addLast(propertySource);
    }
}
