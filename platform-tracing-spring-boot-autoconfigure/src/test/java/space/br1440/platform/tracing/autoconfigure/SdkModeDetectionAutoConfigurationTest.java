package space.br1440.platform.tracing.autoconfigure;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.support.SdkMode;
import space.br1440.platform.tracing.autoconfigure.support.SdkModeDiagnostics;
import space.br1440.platform.tracing.core.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.core.facade.NoopTraceOperations;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-3 (Фаза 15): wiring mode detection в {@link TracingCoreAutoConfiguration}.
 * <p>
 * Подтверждает ключевые правки по ревью архитектора: фасад (а не NoOp) в external/agent-режиме;
 * NoOp только для {@code DISABLED}; starter не создаёт второй {@code OpenTelemetry} bean.
 */
@DisplayName("Фаза 15: mode detection + facade vs NoOp")
class SdkModeDetectionAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TracingCoreAutoConfiguration.class));

    @Test
    @DisplayName("external: пользовательский OpenTelemetry bean → фасад DefaultTraceOperations, НЕ NoOp")
    void agent_mode_provides_facade_not_noop() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class)
                .run(context -> {
                    TraceOperations tracing = context.getBean(TraceOperations.class);
                    assertThat(tracing)
                            .as("при наличии функционального SDK фасад должен делегировать в него, а не NoOp")
                            .isInstanceOf(DefaultTraceOperations.class)
                            .isNotInstanceOf(NoopTraceOperations.class);

                    SdkModeDiagnostics diagnostics = context.getBean(SdkModeDiagnostics.class);
                    assertThat(diagnostics.mode()).isEqualTo(SdkMode.EXTERNAL);
                });
    }

    @Test
    @DisplayName("DISABLED → NoopTraceOperations даже при наличии OpenTelemetry bean")
    void disabled_yields_noop() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class)
                .withPropertyValues("platform.tracing.sdk.mode=DISABLED")
                .run(context -> {
                    TraceOperations tracing = context.getBean(TraceOperations.class);
                    assertThat(tracing)
                            .as("DISABLED — единственный режим с NoOp")
                            .isInstanceOf(NoopTraceOperations.class);
                    assertThat(context.getBean(SdkModeDiagnostics.class).mode()).isEqualTo(SdkMode.DISABLED);
                });
    }

    @Test
    @DisplayName("starter: нет агента и нет bean → starter НЕ создаёт OpenTelemetry SDK bean")
    void agent_mode_does_not_create_sdk() {
        contextRunner.run(context -> {
            assertThat(context)
                    .as("starter agent-first: SDK не создаётся стартером")
                    .doesNotHaveBean(OpenTelemetry.class);
            // Без функционального SDK фасад остаётся NoOp (поведение не изменилось; режим STARTER).
            assertThat(context.getBean(TraceOperations.class)).isInstanceOf(NoopTraceOperations.class);
            assertThat(context.getBean(SdkModeDiagnostics.class).mode()).isEqualTo(SdkMode.STARTER);
        });
    }

    @Configuration
    static class OpenTelemetryConfiguration {
        @Bean
        public OpenTelemetry openTelemetry() {
            return OpenTelemetrySdk.builder().build();
        }
    }
}
