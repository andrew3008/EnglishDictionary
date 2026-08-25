package space.br1440.platform.tracing.autoconfigure.integrations.observation;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.SmartInitializingSingleton;
import space.br1440.platform.tracing.autoconfigure.agent.OtelJavaAgentMarkerDetector;

/**
 * Стартовый WARN по согласованности {@code suppress-micrometer-http-observations} и присутствия Agent.
 * <p>
 * Штатный стартер не содержит {@code micrometer-tracing-bridge-otel}: HTTP SERVER span
 * публикует только Agent. Suppress гасит Micrometer HTTP observations
 * ({@code http.server.requests} / {@code http.client.requests}), а не второй OTel-продюсер.
 * Agent + application-owned {@code OpenTelemetry} (типичный эффект bridge) — отдельный fail-closed.
 * <pre>
 *   suppress=true,  agent=yes — Agent spans есть, HTTP-метрики Micrometer выключены — WARN нет;
 *   suppress=true,  agent=no  — нет Agent spans и нет HTTP-метрик Micrometer — WARN (дыра);
 *   suppress=false, agent=yes — Agent spans + HTTP-метрики Micrometer рядом — WARN;
 *   suppress=false, agent=no  — только Micrometer observations — WARN нет.
 * </pre>
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class HttpObservationSuppressionStartupRunner implements SmartInitializingSingleton {

    private final boolean suppressEnabled;
    private final boolean agentDetected;

    @Override
    public void afterSingletonsInstantiated() {
        if (suppressEnabled && !agentDetected) {
            log.warn("platform.tracing.suppression.suppress-micrometer-http-observations=true is enabled, but "
                    + "OpenTelemetry Java Agent was not detected in the JVM. HTTP Micrometer observations "
                    + "(http.server.requests / http.client.requests) are suppressed and the Agent is not "
                    + "creating HTTP spans. Disable suppression or attach the Controlled Platform Agent.");
            return;
        }

        if (!suppressEnabled && agentDetected) {
            log.warn("OpenTelemetry Java Agent detected, but "
                    + "platform.tracing.suppression.suppress-micrometer-http-observations=false. "
                    + "Micrometer HTTP observations remain enabled: http.server.requests and "
                    + "http.client.requests metrics are published alongside Agent HTTP spans. "
                    + "The standard starter does not add a second HTTP span producer. "
                    + "Set platform.tracing.suppression.suppress-micrometer-http-observations=true to "
                    + "suppress those observations.");
        }
    }

    static HttpObservationSuppressionStartupRunner create(boolean suppressEnabled) {
        return new HttpObservationSuppressionStartupRunner(
                suppressEnabled, OtelJavaAgentMarkerDetector.isAgentPresent());
    }
}
