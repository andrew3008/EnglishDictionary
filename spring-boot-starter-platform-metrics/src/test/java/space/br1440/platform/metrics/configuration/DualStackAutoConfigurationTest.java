package space.br1440.platform.metrics.configuration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import space.br1440.platform.metrics.http.HttpMetricsFilter;
import space.br1440.platform.metrics.webflux.WebfluxMetricsFilter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Dual-stack classpath auto-configuration")
class DualStackAutoConfigurationTest {

    private static String[] defaultPropertiesAsPairs() {
        Properties props = new Properties();
        try (InputStream in = DualStackAutoConfigurationTest.class.getResourceAsStream("/default.properties")) {
            if (in == null) {
                throw new IllegalStateException("classpath:default.properties not found");
            }
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return props.stringPropertyNames().stream()
                .map(name -> name + "=" + props.getProperty(name))
                .toArray(String[]::new);
    }

    @Test
    void servletWebType() {
        new WebApplicationContextRunner()
                .withPropertyValues(defaultPropertiesAsPairs())
                .withPropertyValues("spring.main.web-application-type=servlet")
                .withBean(SimpleMeterRegistry.class)
                .withConfiguration(AutoConfigurations.of(
                        CommonMetricConfiguration.class,
                        HttpMetricConfiguration.class,
                        HttpWebfluxMetricConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(HttpMetricsFilter.class);
                    assertThat(context).doesNotHaveBean(WebfluxMetricsFilter.class);
                });
    }

    @Test
    void reactiveWebType() {
        new ReactiveWebApplicationContextRunner()
                .withPropertyValues(defaultPropertiesAsPairs())
                .withBean(SimpleMeterRegistry.class)
                .withConfiguration(AutoConfigurations.of(
                        CommonMetricConfiguration.class,
                        HttpMetricConfiguration.class,
                        HttpWebfluxMetricConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(WebfluxMetricsFilter.class);
                    assertThat(context).doesNotHaveBean(HttpMetricsFilter.class);
                });
    }

    @Test
    void noneWebType() {
        new ApplicationContextRunner()
                .withPropertyValues(defaultPropertiesAsPairs())
                .withPropertyValues("spring.main.web-application-type=none")
                .withBean(SimpleMeterRegistry.class)
                .withConfiguration(AutoConfigurations.of(
                        CommonMetricConfiguration.class,
                        HttpMetricConfiguration.class,
                        HttpWebfluxMetricConfiguration.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(HttpMetricsFilter.class);
                    assertThat(context).doesNotHaveBean(WebfluxMetricsFilter.class);
                });
    }
}
