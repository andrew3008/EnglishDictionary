package space.br1440.platform.tracing.otel.javaagent.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;

class DegradedModeControllerTest {

    @Test
    void degrades_when_component_breaker_opens_and_publishes_flag() {
        // given
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        DegradedModeController controller = new DegradedModeController(
                diagnostics,
                new CircuitBreaker("sampler", 2, 60_000L),
                new CircuitBreaker("processor", 2, 60_000L));

        assertThat(controller.isDegraded()).isFalse();
        assertThat(diagnostics.isDegradedMode()).isFalse();

        // when
        controller.recordFailure(DegradedModeController.Component.SAMPLER);
        controller.recordFailure(DegradedModeController.Component.SAMPLER);

        // then
        assertThat(controller.isDegraded()).as("breaker sampler открыт").isTrue();
        assertThat(diagnostics.isDegradedMode()).as("флаг опубликован в diagnostics").isTrue();
    }

    @Test
    void processor_success_does_not_affect_open_sampler() {
        // given
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        DegradedModeController controller = new DegradedModeController(
                diagnostics,
                new CircuitBreaker("sampler", 1, 60_000L),
                new CircuitBreaker("processor", 1, 60_000L));

        controller.recordFailure(DegradedModeController.Component.SAMPLER);
        assertThat(controller.isDegraded()).isTrue();

        // when
        controller.recordSuccess(DegradedModeController.Component.PROCESSOR);

        // then
        assertThat(controller.isDegraded()).as("sampler всё ещё OPEN").isTrue();
    }
}
