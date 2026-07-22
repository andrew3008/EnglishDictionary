package space.br1440.platform.tracing.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.aspect.TracedAspect;
import space.br1440.platform.tracing.autoconfigure.health.TracingHealthIndicator;
import space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingMetrics;
import space.br1440.platform.tracing.autoconfigure.support.ControlledAgentTestRuntime;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;

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

    private final ApplicationContextRunner controlledAgentRunner = contextRunner
            .withInitializer(ControlledAgentTestRuntime::initialize);

    @Test
    void регистрируетTraceOperationsПриОтсутствииOpenTelemetry() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("CONTROLLED_AGENT_MISSING");
        });
    }

    @Test
    void регистрируетDefaultTraceOperationsПриНаличииOpenTelemetry() {
        controlledAgentRunner
                .run(context -> {
                    TraceOperations traceOperations = context.getBean(TraceOperations.class);
                    assertThat(traceOperations).isNotNull();
                    assertThat(traceOperations).isNotInstanceOf(NoopTraceOperations.class);
                });
    }

    @Test
    void отключаетсяПриЯвномВыключении() {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.enabled=false",
                        "platform.tracing.sdk.mode=DISABLED")
                .run(context -> {
                    assertThat(context).hasSingleBean(TraceOperations.class);
                    assertThat(context.getBean(TraceOperations.class)).isInstanceOf(NoopTraceOperations.class);
                    assertThat(context).doesNotHaveBean(TracedAspect.class);
                });
    }

    @Test
    void декорируетPlatformTracingMetricsПриНаличииMeterRegistry() {
        controlledAgentRunner
                .withUserConfiguration(MeterRegistryConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(PlatformTracingMetrics.class);
                    assertThat(context).doesNotHaveBean("meteredTraceOperations");
                    TraceOperations primary = context.getBean(TraceOperations.class);
                    assertThat(primary).isInstanceOf(DefaultTraceOperations.class);
                });
    }

    @Test
    void регистрируетAspectДляAnnotation() {
        controlledAgentRunner.run(context -> {
            assertThat(context).hasSingleBean(TracedAspect.class);
        });
    }

    @Test
    void регистрируетHealthIndicatorИEndpoint() {
        controlledAgentRunner
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
    static class MeterRegistryConfiguration {
        @Bean
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
