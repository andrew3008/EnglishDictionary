package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

class DuplicateSpansRegressionMatrixTest {

    private final ReactiveWebApplicationContextRunner contextRunner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ObservationAutoConfiguration.class,
                    WebFluxHttpObservationSuppressionAutoConfiguration.class));

    @Test
    @DisplayName("[suppress off] suppressor bean is absent — Micrometer Observation works normally")
    void suppressorAbsent_whenSuppressOff() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-http-observations=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("platformWebFluxHttpObservationSuppressor"));
    }

    @Test
    @DisplayName("[suppress off, default] suppressor bean is absent by default")
    void suppressorAbsent_byDefault() {
        contextRunner.run(ctx -> assertThat(ctx).doesNotHaveBean("platformWebFluxHttpObservationSuppressor"));
    }

    @Test
    @DisplayName("[suppress on] suppressor bean is active — reactive HTTP observations are suppressed")
    void suppressorActive_whenSuppressOn() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-http-observations=true")
                .run(ctx -> assertThat(ctx).hasBean("platformWebFluxHttpObservationSuppressor"));
    }

    @Test
    @DisplayName("[Agent on, suppress on] combination = standard prod scenario: suppressor is active")
    void productionScenario_suppressorActive() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-http-observations=true")
                .run(ctx -> assertThat(ctx).hasBean("platformWebFluxHttpObservationSuppressor"));
    }
}
