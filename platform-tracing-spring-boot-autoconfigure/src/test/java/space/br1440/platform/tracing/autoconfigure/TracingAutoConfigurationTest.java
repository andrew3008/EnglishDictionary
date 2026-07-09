package space.br1440.platform.tracing.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.autoconfigure.aspect.TracedAspect;
import space.br1440.platform.tracing.autoconfigure.health.TracingHealthIndicator;
import space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingMetrics;
import space.br1440.platform.tracing.core.facade.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.facade.NoOpPlatformTracing;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты авто-конфигураций платформенной трассировки. Используются {@link ApplicationContextRunner}
 * для проверки условных правил ({@code ConditionalOnClass}, {@code ConditionalOnBean},
 * {@code ConditionalOnProperty}) без поднятия полноценного Spring Boot приложения.
 */
class TracingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingCoreAutoConfiguration.class,
                    TracingMetricsAutoConfiguration.class,
                    TracingAopAutoConfiguration.class,
                    TracingObservationAutoConfiguration.class,
                    TracingActuatorAutoConfiguration.class
            ));

    @Test
    void регистрируетPlatformTracingПриОтсутствииOpenTelemetry() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PlatformTracing.class);
            // Без OpenTelemetry-бина и Java Agent'а активна безоперационная заглушка.
            assertThat(context.getBean(PlatformTracing.class)).isInstanceOf(NoOpPlatformTracing.class);
        });
    }

    @Test
    void регистрируетDefaultPlatformTracingПриНаличииOpenTelemetry() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(OpenTelemetry.class);
                    PlatformTracing platformTracing = context.getBean(PlatformTracing.class);
                    assertThat(platformTracing).isNotNull();
                    assertThat(platformTracing).isNotInstanceOf(NoOpPlatformTracing.class);
                });
    }

    @Test
    void отключаетсяПриЯвномВыключении() {
        contextRunner
                .withPropertyValues("platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PlatformTracing.class);
                    assertThat(context).doesNotHaveBean(TracedAspect.class);
                });
    }

    @Test
    void декорируетPlatformTracingMetricsПриНаличииMeterRegistry() {
        contextRunner
                .withUserConfiguration(MeterRegistryConfiguration.class, OpenTelemetryConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(PlatformTracingMetrics.class);
                    assertThat(context).doesNotHaveBean("meteredPlatformTracing");
                    PlatformTracing primary = context.getBean(PlatformTracing.class);
                    assertThat(primary).isInstanceOf(DefaultPlatformTracing.class);
                });
    }

    @Test
    void регистрируетAspectДляAnnotation() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TracedAspect.class);
        });
    }

    @Test
    void регистрируетHealthIndicatorИEndpoint() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(
                        EndpointAutoConfiguration.class,
                        HealthEndpointAutoConfiguration.class
                ))
                .withPropertyValues(
                        "management.endpoint.health.enabled=true",
                        "management.health.tracing.enabled=true",
                        "management.endpoints.web.exposure.include=*"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(TracingHealthIndicator.class);
                });
    }

    // Тест регистрации Servlet WebFilter'ов вынесен в модуль platform-tracing-autoconfigure-webmvc,
    // вместе с самим ServletTracingAutoConfiguration. Аналогично — для WebFlux-аналога.

    @Configuration
    static class OpenTelemetryConfiguration {
        @Bean
        public OpenTelemetry openTelemetry() {
            // Тестовый SDK без экспортёров — span'ы создаются, но не уходят за пределы JVM.
            return OpenTelemetrySdk.builder().build();
        }
    }

    @Configuration
    static class MeterRegistryConfiguration {
        @Bean
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
