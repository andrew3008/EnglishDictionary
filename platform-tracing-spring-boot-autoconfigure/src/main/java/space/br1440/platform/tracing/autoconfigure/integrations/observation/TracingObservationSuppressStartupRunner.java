package space.br1440.platform.tracing.autoconfigure.integrations.observation;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import space.br1440.platform.tracing.autoconfigure.agent.OtelAgentDetector;

/**
 * Стартовый WARN-сигнал, обращающий внимание оператора на несогласованную конфигурацию
 * {@code platform.tracing.suppression.suppress-micrometer-tracing} и присутствия OpenTelemetry Java Agent в текущей JVM.
 * <p>
 * Матрица 2×2 поведения:
 * <pre>
 *   suppress=true,  agent=yes — штатный сценарий (Agent создаёт HTTP-span'ы, Micrometer подавлен) — WARN не выдаём;
 *   suppress=true,  agent=no  — Agent не подключён, но Micrometer подавлен → дыра в HTTP-телеметрии — WARN;
 *   suppress=false, agent=yes — Micrometer и Agent работают параллельно → дублирующиеся span'ы — WARN;
 *   suppress=false, agent=no  — default-сценарий, всё корректно — WARN не выдаём (тихое поведение).
 * </pre>
 * Логика выполняется один раз при инициализации singleton-бинов; повторных уведомлений в рантайме нет.
 * Для подавления warning'а в тестах допускается перенастроить {@code platform.tracing.*}
 * либо переопределить bean в потребительской конфигурации.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class TracingObservationSuppressStartupRunner implements SmartInitializingSingleton {

    private final boolean suppressEnabled;
    private final boolean agentDetected;

    @Override
    public void afterSingletonsInstantiated() {
        if (suppressEnabled && !agentDetected) {
            log.warn("platform.tracing.suppression.suppress-micrometer-tracing=true is enabled, but "
                    + "OpenTelemetry Java Agent was not detected in the JVM. HTTP spans will not be created "
                    + "by Micrometer Observation (suppressed) nor by the Agent. Disable suppression or "
                    + "attach the Agent (-javaagent:opentelemetry-javaagent.jar).");
            return;
        }

        if (!suppressEnabled && agentDetected) {
            log.warn("OpenTelemetry Java Agent detected, but "
                    + "platform.tracing.suppression.suppress-micrometer-tracing=false. HTTP spans may be "
                    + "duplicated: both the Agent and Micrometer Observation create them. "
                    + "Set platform.tracing.suppression.suppress-micrometer-tracing=true.");
        }
    }

    static TracingObservationSuppressStartupRunner create(boolean suppressEnabled) {
        return new TracingObservationSuppressStartupRunner(suppressEnabled, OtelAgentDetector.isAgentPresent());
    }
}
