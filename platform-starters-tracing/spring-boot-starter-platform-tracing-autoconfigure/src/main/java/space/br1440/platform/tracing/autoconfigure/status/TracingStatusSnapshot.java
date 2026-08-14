package space.br1440.platform.tracing.autoconfigure.status;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsLeaves;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AgentReadinessSnapshot;

public record TracingStatusSnapshot(int schemaVersion,
                                    boolean tracingEnabled,
                                    String authority,
                                    Map<String, Object> agent,
                                    Map<String, Object> runtime,
                                    SettingsStatus settings,
                                    List<Map<String, Object>> jmxContractDiagnostics) {

    public static final int CURRENT_SCHEMA_VERSION = 3;

    public TracingStatusSnapshot {
        agent = immutableCopy(agent);
        runtime = immutableCopy(runtime);
        jmxContractDiagnostics = List.copyOf(jmxContractDiagnostics);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public record SettingsStatus(boolean available,
                               SettingsAbsenceReason reason,
                               AgentReadinessSnapshot readiness,
                               AcceptedSettingsLeaves accepted) {

        public static SettingsStatus available(AgentReadinessSnapshot readiness,
                                             AcceptedSettingsLeaves accepted) {
            return new SettingsStatus(true, null, readiness, accepted);
        }

        public static SettingsStatus absent(SettingsAbsenceReason reason) {
            return new SettingsStatus(false, reason, null, null);
        }
    }
}
