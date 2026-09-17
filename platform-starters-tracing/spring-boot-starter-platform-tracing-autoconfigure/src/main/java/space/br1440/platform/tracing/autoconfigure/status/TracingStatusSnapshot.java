package space.br1440.platform.tracing.autoconfigure.status;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannel;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedPolicySummary;

public record TracingStatusSnapshot(int schemaVersion,
                                    boolean tracingEnabled,
                                    TracingControlChannel controlChannel,
                                    Map<String, Object> agent,
                                    Map<String, Object> runtime,
                                    SettingsStatus settings,
                                    List<Map<String, Object>> jmxContractDiagnostics) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

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
                                 ExpectedSettingsRevision acceptedRevision,
                                 AcceptedPolicySummary accepted) {

        public static SettingsStatus available(ExpectedSettingsRevision acceptedRevision,
                                               AcceptedPolicySummary accepted) {
            return new SettingsStatus(true, null, acceptedRevision, accepted);
        }

        public static SettingsStatus absent(SettingsAbsenceReason reason) {
            return new SettingsStatus(false, reason, null, null);
        }
    }
}
