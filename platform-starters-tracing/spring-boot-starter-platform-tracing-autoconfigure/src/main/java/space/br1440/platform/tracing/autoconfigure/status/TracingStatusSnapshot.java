package space.br1440.platform.tracing.autoconfigure.status;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannel;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;

public record TracingStatusSnapshot(boolean tracingEnabled,
                                    TracingControlChannel controlChannel,
                                    Map<String, Object> agent,
                                    Map<String, Object> runtime,
                                    SettingsStatus settings,
                                    List<Map<String, Object>> jmxContractDiagnostics) {

    public TracingStatusSnapshot {
        Objects.requireNonNull(controlChannel, "controlChannel");
        agent = immutableCopy(agent);
        runtime = immutableCopy(runtime);
        jmxContractDiagnostics = List.copyOf(jmxContractDiagnostics);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public record SettingsStatus(boolean available,
                                 SettingsAbsenceReason reason,
                                 SettingsRevision acceptedRevision,
                                 CompleteTracingSettings accepted,
                                 String sourceAuthority) {

        public static SettingsStatus available(SettingsRevision acceptedRevision,
                                               CompleteTracingSettings accepted,
                                               String sourceAuthority) {
            return new SettingsStatus(true, null, acceptedRevision, accepted, sourceAuthority);
        }

        public static SettingsStatus absent(SettingsAbsenceReason reason) {
            return new SettingsStatus(false, reason, null, null, null);
        }
    }
}
