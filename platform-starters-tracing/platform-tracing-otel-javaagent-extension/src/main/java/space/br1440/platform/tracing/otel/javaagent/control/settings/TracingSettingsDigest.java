package space.br1440.platform.tracing.otel.javaagent.control.settings;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedTracingSettingsDigest;

/**
 * Адаптирует доменную модель настроек к сгенерированному алгоритму wire-digest.
 * Каноническая сериализация и ограничения принадлежат JMX manifest и не дублируются здесь.
 */
public final class TracingSettingsDigest {

    private TracingSettingsDigest() {
    }

    public static String sha256(TracingSettings policy) {
        Objects.requireNonNull(policy, "policy");
        return GeneratedTracingSettingsDigest.sha256(
                policy.samplingEnabled(),
                policy.samplingRatio(),
                policy.samplingRouteRatios(),
                policy.validationEnabled(),
                policy.validationStrict(),
                policy.exporterEnabled(),
                policy.traceControlHeadersEnabled(),
                policy.diagnosticsLogLevel().name()
        );
    }
}
