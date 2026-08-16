package space.br1440.platform.tracing.otel.javaagent.resilience;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DegradedModeController}: агрегирует состояние per-component breaker'ов и публикует
 * признак degraded в {@link TracingDiagnostics}. Scope — только SAMPLER/PROCESSOR.
 */
class DegradedModeControllerTest {

    @Test
    void degrades_when_component_breaker_opens_and_publishes_flag() {
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        DegradedModeController controller = new DegradedModeController(
                diagnostics,
                new CircuitBreaker("sampler", 2, 60_000L),
                new CircuitBreaker("processor", 2, 60_000L));

        assertThat(controller.isDegraded()).isFalse();
        assertThat(diagnostics.isDegradedMode()).isFalse();

        controller.recordFailure(DegradedModeController.Component.SAMPLER);
        controller.recordFailure(DegradedModeController.Component.SAMPLER);

        assertThat(controller.isDegraded()).as("breaker sampler открыт").isTrue();
        assertThat(diagnostics.isDegradedMode()).as("флаг опубликован в diagnostics").isTrue();
    }

    @Test
    void processor_success_does_not_affect_open_sampler() {
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        DegradedModeController controller = new DegradedModeController(
                diagnostics,
                new CircuitBreaker("sampler", 1, 60_000L),
                new CircuitBreaker("processor", 1, 60_000L));

        controller.recordFailure(DegradedModeController.Component.SAMPLER);
        assertThat(controller.isDegraded()).isTrue();

        controller.recordSuccess(DegradedModeController.Component.PROCESSOR);
        assertThat(controller.isDegraded()).as("sampler всё ещё OPEN").isTrue();
    }
}
