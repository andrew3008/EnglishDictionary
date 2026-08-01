package space.br1440.platform.tracing.autoconfigure.status;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import space.br1440.platform.tracing.autoconfigure.control.policy.AcceptedPolicyLeaves;
import space.br1440.platform.tracing.autoconfigure.control.policy.AgentReadinessView;

public record TracingStatusSnapshot(int schemaVersion,
                                    boolean tracingEnabled,
                                    String authority,
                                    Map<String, Object> agent,
                                    Map<String, Object> runtime,
                                    PolicyStatus policy) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public TracingStatusSnapshot {
        agent = immutableCopy(agent);
        runtime = immutableCopy(runtime);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public record PolicyStatus(boolean available,
                               PolicyAbsenceReason reason,
                               AgentReadinessView readiness,
                               AcceptedPolicyLeaves accepted) {

        public static PolicyStatus available(AgentReadinessView readiness,
                                             AcceptedPolicyLeaves accepted) {
            return new PolicyStatus(true, null, readiness, accepted);
        }

        public static PolicyStatus absent(PolicyAbsenceReason reason) {
            return new PolicyStatus(false, reason, null, null);
        }
    }
}
