package space.br1440.platform.tracing.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import space.br1440.platform.tracing.autoconfigure.support.OtelAgentDetector;

/**
 * Стартовый WARN-сигнал, обращающий внимание оператора на несогласованную конфигурацию
 * {@code platform.tracing.suppression.suppress-micrometer-tracing} и присутствия OpenTelemetry
 * Java Agent в текущей JVM.
 * <p>
 * Матрица 2×2 поведения:
 * <pre>
 *   suppress=true,  agent=yes — штатный сценарий (Agent создаёт HTTP-span'ы, Micrometer подавлен) — WARN не выдаём;
 *   suppress=true,  agent=no  — Agent не подключён, но Micrometer подавлен → дыра в HTTP-телеметрии — WARN;
 *   suppress=false, agent=yes — Micrometer и Agent работают параллельно → дублирующиеся span'ы — WARN;
 *   suppress=false, agent=no  — default-сценарий, всё корректно — WARN не выдаём (тихое поведение).
 * </pre>
 * Логика выполняется один раз при инициализации singleton-бинов; повторных уведомлений в рантайме
 * нет. Для подавления warning'а в тестах допускается перенастроить {@code platform.tracing.*}
 * либо переопределить bean в потребительской конфигурации.
 */
class TracingObservationSuppressStartupRunner implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(TracingObservationSuppressStartupRunner.class);

    private final boolean suppressEnabled;
    private final boolean agentDetected;

    TracingObservationSuppressStartupRunner(boolean suppressEnabled, boolean agentDetected) {
        this.suppressEnabled = suppressEnabled;
        this.agentDetected = agentDetected;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (suppressEnabled && !agentDetected) {
            // Suppress включён, но Agent не обнаружен → HTTP-span'ы вообще не создаются никем.
            log.warn("Включено platform.tracing.suppression.suppress-micrometer-tracing=true, но "
                    + "OpenTelemetry Java Agent не обнаружен в JVM. HTTP-span'ы не будут создаваться "
                    + "ни Micrometer Observation (подавлен), ни Agent'ом. Отключите подавление либо "
                    + "подключите Agent (-javaagent:opentelemetry-javaagent.jar).");
            return;
        }
        if (!suppressEnabled && agentDetected) {
            // Agent обнаружен, но suppress выключен → дублирование HTTP-span'ов.
            log.warn("Обнаружен OpenTelemetry Java Agent, но "
                    + "platform.tracing.suppression.suppress-micrometer-tracing=false. Возможно "
                    + "дублирование HTTP-span'ов: их создаёт и Agent, и Micrometer Observation. "
                    + "Рекомендуется включить platform.tracing.suppression.suppress-micrometer-tracing=true.");
        }
        // Остальные две ячейки (true/yes — штатный сценарий; false/no — default) не требуют WARN.
    }

    /**
     * Фабричный метод: получает Agent-детект через {@link OtelAgentDetector}. Изолирует runtime-проверку
     * для удобства unit-тестирования (в тестах используется конструктор с явными флагами).
     */
    static TracingObservationSuppressStartupRunner create(boolean suppressEnabled) {
        return new TracingObservationSuppressStartupRunner(suppressEnabled, OtelAgentDetector.isAgentPresent());
    }
}
