package space.br1440.platform.tracing.autoconfigure.diagnostics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.metrics.TracingMetricsAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationAuthorityAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingRuntime;
import space.br1440.platform.tracing.autoconfigure.support.ControlledAgentTestRuntime;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.state.TracingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 7: extended Spring Boot autoconfiguration matrix beyond Slice 2 {@code BeanTopologyTest}.
 */
class SpringBootContextMatrixTest {

    private final ApplicationContextRunner baseRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingConfigurationAuthorityAutoConfiguration.class,
                    TracingRuntimeAutoConfiguration.class,
                    TracingRuntimeDiagnosticsAutoConfiguration.class,
                    TracingMetricsAutoConfiguration.class))
            .withPropertyValues(
                    "platform.tracing.configuration.authority=HELM",
                    "platform.environment=LOCAL");

    private final ApplicationContextRunner controlledAgentRunner = baseRunner
            .withInitializer(ControlledAgentTestRuntime::initialize);

    @Test
    void enabledWithOpenTelemetry_exposesEnabledSpanFactoryDiagnostics() {
        controlledAgentRunner
                .run(context -> {
                    assertThat(context.getBean(TraceOperations.class)).isInstanceOf(DefaultTraceOperations.class);
                    assertThat(context.getBean(TracingRuntime.class))
                            .isInstanceOf(OtelTracingRuntime.class);
                    assertThat(context.getBean(SpanFactoryDiagnostics.class).toActuatorMap().get("mode")).isEqualTo("ENABLED");
                });
    }

    @Test
    void disabledTracingMode_exposesDisabledDiagnosticsAndNoOpFacade() {
        baseRunner
                .withPropertyValues(
                        "platform.tracing.mode=DISABLED")
                .run(context -> {
                    assertThat(context.getBean(TraceOperations.class)).isInstanceOf(NoopTraceOperations.class);
                    assertThat(context.getBean(TracingRuntime.class).state().mode())
                            .isEqualTo(TracingMode.DISABLED_BY_CONFIGURATION);
                    assertThat(context.getBean(SpanFactoryDiagnostics.class).toActuatorMap().get("mode"))
                            .isEqualTo("DISABLED_BY_CONFIGURATION");
                });
    }

    @Test
    void unavailableOpenTelemetry_exposesUnavailableOrNoopDiagnostics() {
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
                    assertThat(context.getBean(SpanFactoryDiagnostics.class).toActuatorMap().get("mode")).isEqualTo("ENABLED");
                });
    }

    @Test
    void micrometerAbsent_keepsOtelTracingRuntimeWithoutMeteredWrap() {
        controlledAgentRunner
                .run(context -> {
                    assertThat(context.getBean(TracingRuntime.class))
                            .isInstanceOf(OtelTracingRuntime.class);
                    assertThat(context.getBean(SpanFactoryDiagnostics.class).toActuatorMap().get("mode")).isEqualTo("ENABLED");
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
