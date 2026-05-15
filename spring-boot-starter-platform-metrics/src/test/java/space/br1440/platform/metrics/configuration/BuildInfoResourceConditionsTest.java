package space.br1440.platform.metrics.configuration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import space.br1440.platform.metrics.buildinfo.BuildInfoEndpoint;
import space.br1440.platform.metrics.buildinfo.BuildInfoMetric;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Условия создания бинов build-info: без {@code classpath:build-info.json} контекст не падает,
 * метрика и web-endpoint не регистрируются; при наличии ресурса (как после {@code processResources}) — регистрируются.
 */
class BuildInfoResourceConditionsTest {

    private static String[] defaultPropertiesAsPairs() {
        Properties props = new Properties();
        try (InputStream in = BuildInfoResourceConditionsTest.class.getResourceAsStream("/default.properties")) {
            if (in == null) {
                throw new IllegalStateException("classpath:default.properties not found (expected from main resources)");
            }
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return props.stringPropertyNames().stream()
                .map(name -> name + "=" + props.getProperty(name))
                .toArray(String[]::new);
    }

    private static final String[] MANAGEMENT_PROPERTIES = {
            "spring.main.web-application-type=servlet",
            "management.endpoints.web.exposure.include=platform",
            "management.endpoint.platform.enabled=true"
    };

    private WebApplicationContextRunner baseRunner() {
        return new WebApplicationContextRunner()
                .withPropertyValues(defaultPropertiesAsPairs())
                .withPropertyValues(MANAGEMENT_PROPERTIES)
                .withBean(SimpleMeterRegistry.class)
                .withConfiguration(AutoConfigurations.of(CommonMetricConfiguration.class));
    }

    @Test
    void buildInfoBeansAbsentWhenClasspathResourceHidden() {
        ClassLoader parent = BuildInfoResourceConditionsTest.class.getClassLoader();
        ClassLoader hiding = new HidingBuildInfoJsonClassLoader(parent);

        baseRunner()
                .withClassLoader(hiding)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(BuildInfoMetric.class);
                    assertThat(ctx).doesNotHaveBean(BuildInfoEndpoint.class);
                });
    }

    @Test
    void buildInfoBeansPresentWhenClasspathResourceExists() {
        baseRunner()
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(BuildInfoMetric.class);
                    assertThat(ctx).hasSingleBean(BuildInfoEndpoint.class);
                });
    }

    /**
     * Делегирует родителю, но скрывает {@code build-info.json} для проверки
     * {@link org.springframework.boot.autoconfigure.condition.ConditionalOnResource}.
     */
    private static final class HidingBuildInfoJsonClassLoader extends ClassLoader {

        HidingBuildInfoJsonClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public URL getResource(String name) {
            if (isBuildInfoClasspathName(name)) {
                return null;
            }
            return super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (isBuildInfoClasspathName(name)) {
                return Collections.emptyEnumeration();
            }
            return super.getResources(name);
        }

        private static boolean isBuildInfoClasspathName(String name) {
            if (name == null) {
                return false;
            }
            return "build-info.json".equals(name)
                    || name.endsWith("/build-info.json")
                    || name.endsWith("\\build-info.json");
        }
    }
}
