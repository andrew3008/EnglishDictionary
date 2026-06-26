package space.br1440.platform.metrics.configuration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import space.br1440.platform.metrics.grpc.CustomServerInterceptor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-тест условий {@link GrpcMetricConfiguration}: classpath spring-grpc + {@link PlatformMetrics}.
 */
class GrpcMetricConfigurationSmokeTest {

    private static String[] defaultPropertiesAsPairs() {
        Properties props = new Properties();
        try (InputStream in = GrpcMetricConfigurationSmokeTest.class.getResourceAsStream("/default.properties")) {
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
    void registersGrpcMetricsInterceptorWhenSpringGrpcAndPlatformMetricsPresent() {
        new ApplicationContextRunner()
                .withPropertyValues(defaultPropertiesAsPairs())
                .withBean(SimpleMeterRegistry.class)
                .withConfiguration(AutoConfigurations.of(
                        CommonMetricConfiguration.class,
                        GrpcMetricConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(CustomServerInterceptor.class);
                });
    }

    @Test
    void grpcInterceptorAbsentWithoutGrpcMetricAutoConfiguration() {
        new ApplicationContextRunner()
                .withPropertyValues(defaultPropertiesAsPairs())
                .withBean(SimpleMeterRegistry.class)
                .withConfiguration(AutoConfigurations.of(CommonMetricConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBeansOfType(CustomServerInterceptor.class)).isEmpty();
                });
    }
}
