package space.br1440.platform.metrics.configuration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import space.br1440.platform.metrics.webflux.WebfluxMetricsFilter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-тесты условий {@link HttpWebfluxMetricConfiguration}: REACTIVE vs не-REACTIVE тип приложения.
 */
class HttpWebfluxMetricConfigurationSmokeTest {

    private static String[] defaultPropertiesAsPairs() {
        Properties props = new Properties();
        try (InputStream in = HttpWebfluxMetricConfigurationSmokeTest.class.getResourceAsStream("/default.properties")) {
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

    @Test
    void registersWebfluxMetricsFilterWhenWebApplicationTypeReactive() {
        new ReactiveWebApplicationContextRunner()
                .withPropertyValues(defaultPropertiesAsPairs())
                .withBean(SimpleMeterRegistry.class)
                .withConfiguration(AutoConfigurations.of(
                        CommonMetricConfiguration.class,
                        HttpWebfluxMetricConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(WebfluxMetricsFilter.class);
                });
    }

    @Test
    void doesNotRegisterWebfluxMetricsFilterWhenWebApplicationTypeServlet() {
        new ApplicationContextRunner()
                .withPropertyValues(defaultPropertiesAsPairs())
                .withPropertyValues("spring.main.web-application-type=servlet")
                .withBean(SimpleMeterRegistry.class)
                .withConfiguration(AutoConfigurations.of(
                        CommonMetricConfiguration.class,
                        HttpWebfluxMetricConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(WebfluxMetricsFilter.class);
                });
    }
}
