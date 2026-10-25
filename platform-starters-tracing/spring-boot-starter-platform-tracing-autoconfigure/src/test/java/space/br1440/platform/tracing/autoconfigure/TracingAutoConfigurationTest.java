package space.br1440.platform.tracing.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup.TracingStartupContractHealthAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.integrations.aop.TracedAspect;
import space.br1440.platform.tracing.autoconfigure.integrations.aop.TracingAopAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.integrations.observation.TracingObservationAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingMetrics;
import space.br1440.platform.tracing.autoconfigure.metrics.TracingMetricsAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.support.ControlledAgentTestRuntime;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class TracingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingControlChannelAutoConfiguration.class,
                    TracingRuntimeAutoConfiguration.class,
                    TracingMetricsAutoConfiguration.class,
                    TracingAopAutoConfiguration.class,
                    TracingObservationAutoConfiguration.class,
                    TracingStartupContractHealthAutoConfiguration.class
            ))
            .withPropertyValues("platform.tracing.control.channel=NONE");

    private final ApplicationContextRunner controlledAgentRunner = contextRunner
            .withInitializer(ControlledAgentTestRuntime::initialize);

    @Test
    void registersTraceOperationsWhenOpenTelemetryIsAbsent() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("CONTROLLED_AGENT_MISSING");
        });
    }

    @Test
    void registersDefaultTraceOperationsWhenOpenTelemetryIsPresent() {
        controlledAgentRunner
                .run(context -> {
                    TraceOperations traceOperations = context.getBean(TraceOperations.class);
                    assertThat(traceOperations).isNotNull();
                    assertThat(traceOperations).isNotInstanceOf(NoopTraceOperations.class);
                });
    }

    @Test
    void disablesWhenExplicitlyTurnedOff() {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(TraceOperations.class);
                    assertThat(context.getBean(TraceOperations.class)).isInstanceOf(NoopTraceOperations.class);
                    assertThat(context).doesNotHaveBean(TracedAspect.class);
                });
    }

    @Test
    void decoratesPlatformTracingMetricsWhenMeterRegistryIsPresent() {
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
    void registersAspectForAnnotation() {
        controlledAgentRunner.run(context -> assertThat(context).hasSingleBean(TracedAspect.class));
    }

    @Test
    void registersHealthIndicatorAndEndpoint() {
        controlledAgentRunner
                .withConfiguration(AutoConfigurations.of(
                        EndpointAutoConfiguration.class,
                        HealthEndpointAutoConfiguration.class
                ))
                .withPropertyValues(
                        "management.endpoint.health.enabled=true",
                        "management.health.tracing-startup-contract.enabled=true",
                        "management.endpoints.web.exposure.include=*"
                )
                .run(context -> assertThat(context).hasBean("tracingStartupContractHealthIndicator"));
    }

    @Configuration
    static class MeterRegistryConfiguration {

        @Bean
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
