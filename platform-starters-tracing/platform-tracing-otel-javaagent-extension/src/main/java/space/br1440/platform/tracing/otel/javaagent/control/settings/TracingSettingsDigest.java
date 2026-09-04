package space.br1440.platform.tracing.otel.javaagent.control.settings;

import java.util.Objects;

import lombok.experimental.UtilityClass;

import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxTracingSettingsDigest;

@UtilityClass
public final class TracingSettingsDigest {

    public static String sha256(TracingSettings policy) {
        Objects.requireNonNull(policy, "policy");

        return JmxTracingSettingsDigest.sha256(
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
