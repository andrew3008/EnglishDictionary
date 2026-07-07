package space.br1440.platform.tracing.autoconfigure.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тест проверяет, что детект OpenTelemetry Java Agent в стандартной junit-JVM возвращает
 * {@code false}: в test classpath нет {@code io.opentelemetry.javaagent.OpenTelemetryAgent},
 * который инжектится bootstrap-classloader'ом только при реальном запуске с {@code -javaagent}.
 * <p>
 * Тест на положительный сценарий присутствия Agent осознанно отсутствует: воспроизвести его
 * в unit-тесте без модификации classloader'а невозможно. Положительный сценарий покрывается
 * E2E-тестами с реальным javaagent.
 */
class OtelAgentDetectorTest {

    @Test
    void returnsFalseWhenAgentNotPresentInTestJvm() {
        assertThat(OtelAgentDetector.isAgentPresent()).isFalse();
    }
}
