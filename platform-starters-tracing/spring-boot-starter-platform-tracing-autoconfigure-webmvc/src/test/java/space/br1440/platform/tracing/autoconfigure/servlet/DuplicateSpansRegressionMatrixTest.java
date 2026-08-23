package space.br1440.platform.tracing.autoconfigure.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * Bean-контракт suppressor'а (Servlet). Штатный стартер не публикует второй HTTP SERVER span:
 * suppress гасит Micrometer HTTP observations (метрики), Agent остаётся единственным
 * span-продюсером. Agent + {@code micrometer-tracing-bridge-otel} fail-closed из-за
 * application-owned {@code OpenTelemetry}.
 *
 * <pre>
 *   Agent off, suppress off → HTTP-метрики Micrometer есть, Agent spans нет
 *   Agent off, suppress on  → нет метрик и нет Agent spans (WARN)
 *   Agent on,  suppress off → 1 Agent span + HTTP-метрики Micrometer (WARN)
 *   Agent on,  suppress on  → 1 Agent span, HTTP-метрик нет
 *   enabled=false           → suppressor не регистрируется
 * </pre>
 */
class DuplicateSpansRegressionMatrixTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ObservationAutoConfiguration.class,
                    WebMvcHttpObservationSuppressionAutoConfiguration.class));

    @Test
    @DisplayName("[suppress off] suppressor bean is absent — Micrometer Observation works normally")
    void suppressorAbsent_whenSuppressOff() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-http-observations=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("platformMvcHttpObservationSuppressor"));
    }

    @Test
    @DisplayName("[suppress off, default] suppressor bean is absent by default")
    void suppressorAbsent_byDefault() {
        contextRunner.run(ctx -> assertThat(ctx).doesNotHaveBean("platformMvcHttpObservationSuppressor"));
    }

    @Test
    @DisplayName("[suppress on] suppressor bean is active — Micrometer Observation is suppressed")
    void suppressorActive_whenSuppressOn() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-http-observations=true")
                .run(ctx -> assertThat(ctx).hasBean("platformMvcHttpObservationSuppressor"));
    }

    @Test
    @DisplayName("[Agent on, suppress on] combination = standard prod scenario: suppressor is active")
    void productionScenario_suppressorActiveAndAgentDetectedConceptually() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-http-observations=true")
                .run(ctx -> assertThat(ctx).hasBean("platformMvcHttpObservationSuppressor"));
    }
}
