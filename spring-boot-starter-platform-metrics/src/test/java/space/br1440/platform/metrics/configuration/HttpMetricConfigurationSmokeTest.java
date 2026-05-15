package space.br1440.platform.metrics.configuration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import space.br1440.platform.metrics.http.HttpMetricsFilter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-тесты условий {@link HttpMetricConfiguration}: SERVLET vs REACTIVE.
 */
class HttpMetricConfigurationSmokeTest {

    private static String[] defaultPropertiesAsPairs() {
        Properties props = new Properties();
        try (InputStream in = HttpMetricConfigurationSmokeTest.class.getResourceAsStream("/default.properties")) {
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

    /** Servlet-контекст через {@link WebApplicationContextRunner} — иначе {@code @ConditionalOnWebApplication(SERVLET)} не матчится. */
    @Test
    void registersHttpMetricsFilterWhenWebApplicationTypeServlet() {
        new WebApplicationContextRunner()
                .withPropertyValues(defaultPropertiesAsPairs())
                .withPropertyValues("spring.main.web-application-type=servlet")
                .withBean(SimpleMeterRegistry.class)
                .withConfiguration(AutoConfigurations.of(
                        CommonMetricConfiguration.class,
                        HttpMetricConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(HttpMetricsFilter.class);
                });
    }

    @Test
    void doesNotRegisterHttpMetricsFilterWhenWebApplicationTypeReactive() {
        new ReactiveWebApplicationContextRunner()
                .withPropertyValues(defaultPropertiesAsPairs())
                .withBean(SimpleMeterRegistry.class)
                .withConfiguration(AutoConfigurations.of(
                        CommonMetricConfiguration.class,
                        HttpMetricConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(HttpMetricsFilter.class);
                });
    }
}
