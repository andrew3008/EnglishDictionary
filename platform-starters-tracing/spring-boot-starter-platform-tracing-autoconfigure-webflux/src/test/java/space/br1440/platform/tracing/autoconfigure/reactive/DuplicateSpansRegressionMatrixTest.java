package space.br1440.platform.tracing.autoconfigure.reactive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регрессионный startup-контракт по матрице 2×2 (Agent on/off × suppress on/off)
 * для предотвращения дублирования HTTP-span'ов (WebFlux-flavor).
 *
 * <p>Conceptual runtime expectation см. в Javadoc Servlet-зеркала
 * {@code DuplicateSpansRegressionMatrixTest} в {@code platform-tracing-autoconfigure-webmvc}.</p>
 *
 * <p>WARN-сторона startup-контракта — {@code TracingObservationSuppressStartupTest}
 * в модуле {@code platform-tracing-spring-boot-autoconfigure}.</p>
 *
 * <p>Реальное число HTTP span'ов в production-сценарии — отдельный e2e
 * {@code DuplicateHttpSpanAgentSmokeTest}.</p>
 */
class DuplicateSpansRegressionMatrixTest {

    private final ReactiveWebApplicationContextRunner contextRunner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ObservationAutoConfiguration.class,
                    WebFluxSuppressMicrometerTracingAutoConfiguration.class));

    @Test
    @DisplayName("[suppress off] suppressor bean отсутствует — Micrometer Observation работает штатно")
    void suppressorAbsent_whenSuppressOff() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-tracing=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("platformWebFluxHttpObservationSuppressor"));
    }

    @Test
    @DisplayName("[suppress off, default] suppressor bean отсутствует по умолчанию")
    void suppressorAbsent_byDefault() {
        contextRunner.run(ctx -> assertThat(ctx).doesNotHaveBean("platformWebFluxHttpObservationSuppressor"));
    }

    @Test
    @DisplayName("[suppress on] suppressor bean активен — реактивные HTTP-обсервации подавлены")
    void suppressorActive_whenSuppressOn() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-tracing=true")
                .run(ctx -> assertThat(ctx).hasBean("platformWebFluxHttpObservationSuppressor"));
    }

    @Test
    @DisplayName("[Agent on, suppress on] комбинация = штатный prod-сценарий: suppressor активен")
    void productionScenario_suppressorActive() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-tracing=true")
                .run(ctx -> assertThat(ctx).hasBean("platformWebFluxHttpObservationSuppressor"));
    }
}
