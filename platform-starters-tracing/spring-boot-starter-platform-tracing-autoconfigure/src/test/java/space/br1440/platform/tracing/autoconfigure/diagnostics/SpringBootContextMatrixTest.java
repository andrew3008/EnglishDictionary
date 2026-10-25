package space.br1440.platform.tracing.autoconfigure.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingRuntime;
import space.br1440.platform.tracing.autoconfigure.metrics.TracingMetricsAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.support.ControlledAgentTestRuntime;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.state.TracingRuntimeMode;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class SpringBootContextMatrixTest {

    private final ApplicationContextRunner baseRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingControlChannelAutoConfiguration.class,
                    TracingRuntimeAutoConfiguration.class,
                    TracingRuntimeDiagnosticsAutoConfiguration.class,
                    TracingMetricsAutoConfiguration.class))
            .withPropertyValues("platform.tracing.control.channel=NONE");

    private final ApplicationContextRunner controlledAgentRunner = baseRunner
            .withInitializer(ControlledAgentTestRuntime::initialize);

    @Test
    void enabledWithOpenTelemetry_exposesEnabledRuntime() {
        controlledAgentRunner
                .run(context -> {
                    assertThat(context.getBean(TraceOperations.class)).isInstanceOf(DefaultTraceOperations.class);
                    assertThat(context.getBean(TracingRuntime.class))
                            .isInstanceOf(OtelTracingRuntime.class);
                    assertThat(context.getBean(TracingRuntime.class).state().mode())
                            .isEqualTo(TracingRuntimeMode.ENABLED);
                });
    }

    @Test
    void disabledTracingMode_exposesDisabledRuntimeAndNoOpFacade() {
        baseRunner
                .withPropertyValues(
                        "platform.tracing.enabled=false")
                .run(context -> {
                    assertThat(context.getBean(TraceOperations.class)).isInstanceOf(NoopTraceOperations.class);
                    assertThat(context.getBean(TracingRuntime.class).state().mode())
                            .isEqualTo(TracingRuntimeMode.DISABLED_BY_CONFIGURATION);
                });
    }

    @Test
    void unavailableOpenTelemetry_failsStartup() {
        baseRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("CONTROLLED_AGENT_MISSING");
        });
    }

    @Test
    void micrometerPresent_wrapsTracingRuntimeWithMeteredDecorator() {
        controlledAgentRunner
                .withUserConfiguration(MeterRegistryConfiguration.class)
                .run(context -> {
                    assertThat(context.getBean(TracingRuntime.class))
                            .isInstanceOf(MeteredTracingRuntime.class);
                    assertThat(context.getBean(TraceOperations.class)).isInstanceOf(DefaultTraceOperations.class);
                    assertThat(context.getBean(TracingRuntime.class).state().mode())
                            .isEqualTo(TracingRuntimeMode.ENABLED);
                });
    }

    @Test
    void micrometerAbsent_keepsOtelTracingRuntimeWithoutMeteredWrap() {
        controlledAgentRunner
                .run(context -> {
                    assertThat(context.getBean(TracingRuntime.class))
                            .isInstanceOf(OtelTracingRuntime.class);
                    assertThat(context.getBean(TracingRuntime.class).state().mode())
                            .isEqualTo(TracingRuntimeMode.ENABLED);
                });
    }

    @Configuration
    static class MeterRegistryConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
