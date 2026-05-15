package space.br1440.platform.metrics.configuration.processor;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

public class PlatformMetricsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String RESOURCE_PATH = "default.properties";
    private static final String PROPERTY_SOURCE_NAME = "platform.metrics.defaults";

    private final PropertiesPropertySourceLoader propertySourceLoader;
    private final Log log;

    public PlatformMetricsEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.propertySourceLoader = new PropertiesPropertySourceLoader();
        this.log = logFactory.getLog(getClass());
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
        if (!resource.exists()) {
            throw new IllegalStateException("Required classpath resource '" + RESOURCE_PATH + "' not found in platform-metrics starter");
        }

        try {
            List<PropertySource<?>> sources = propertySourceLoader.load(PROPERTY_SOURCE_NAME, resource);
            if (sources.isEmpty()) {
                log.debug("No property sources loaded from classpath:" + RESOURCE_PATH + " (empty file?)");
                return;
            }

            environment.getPropertySources().addLast(sources.getFirst());
            log.debug("Registered platform metrics defaults from classpath:" + RESOURCE_PATH);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load platform metrics defaults from classpath:" + RESOURCE_PATH, ex);
        }
    }
}
