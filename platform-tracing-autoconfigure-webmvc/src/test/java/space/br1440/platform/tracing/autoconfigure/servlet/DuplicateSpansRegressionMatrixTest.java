package space.br1440.platform.tracing.autoconfigure.servlet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регрессионный startup-контракт по матрице 2×2 (Agent on/off × suppress on/off)
 * для предотвращения дублирования HTTP-span'ов (Servlet-flavor).
 *
 * <p><b>Conceptual runtime expectation</b> (не валидируется этим unit-тестом, см. DupSpans.2
 * HTTP smoke в e2e-модуле):</p>
 * <pre>
 *   Agent off, suppress off → 1 span (Micrometer Observation)
 *   Agent off, suppress on  → 0 spans (degraded, WARN на startup)
 *   Agent on,  suppress off → DUPLICATE (2 spans: Agent + Micrometer)  ← WARN на startup
 *   Agent on,  suppress on  → 1 span (Agent)                            ← штатный prod-сценарий
 * </pre>
 *
 * <p>Этот тест валидирует <b>только bean-контракт</b> для каждой клетки матрицы: какие
 * suppressor-бины присутствуют/отсутствуют. WARN-сторона startup-контракта покрывается
 * отдельно: см. {@code TracingObservationSuppressStartupTest} в модуле
 * {@code platform-tracing-spring-boot-autoconfigure} (4 ячейки × WARN/no-WARN, через
 * {@code ListAppender}). Реальное количество HTTP span'ов в production-сценарии —
 * через subprocess + Collector ({@code DuplicateHttpSpanAgentSmokeTest}).</p>
 *
 * <p>Зеркальный регрессионный matrix для WebFlux — в модуле
 * {@code platform-tracing-autoconfigure-webflux}.</p>
 */
class DuplicateSpansRegressionMatrixTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ObservationAutoConfiguration.class,
                    WebMvcSuppressMicrometerTracingAutoConfiguration.class));

    @Test
    @DisplayName("[suppress off] suppressor bean отсутствует — Micrometer Observation работает штатно")
    void suppressorAbsent_whenSuppressOff() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-tracing=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("platformMvcHttpObservationSuppressor"));
    }

    @Test
    @DisplayName("[suppress off, default] suppressor bean отсутствует по умолчанию")
    void suppressorAbsent_byDefault() {
        contextRunner.run(ctx -> assertThat(ctx).doesNotHaveBean("platformMvcHttpObservationSuppressor"));
    }

    @Test
    @DisplayName("[suppress on] suppressor bean активен — Micrometer Observation подавлен")
    void suppressorActive_whenSuppressOn() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-tracing=true")
                .run(ctx -> assertThat(ctx).hasBean("platformMvcHttpObservationSuppressor"));
    }

    @Test
    @DisplayName("[Agent on, suppress on] комбинация = штатный prod-сценарий: suppressor активен")
    void productionScenario_suppressorActiveAndAgentDetectedConceptually() {
        // Agent-сторона унит-тестом не имитируется (detect через системную JVM-инициализацию).
        // Здесь фиксируется только bean-контракт. Agent-side детектируется
        // OtelAgentDetector, и его поведение в combination с suppress=on покрыто
        // TracingObservationSuppressStartupTest#noWarnWhenSuppressTrueAndAgentDetected.
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-tracing=true")
                .run(ctx -> assertThat(ctx).hasBean("platformMvcHttpObservationSuppressor"));
    }
}
