package space.br1440.platform.tracing.otel.javaagent.control;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyView;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshot;

/**
 * Формирует read-only представление фактически принятой runtime-политики.
 *
 * <p>Все восемь Category A значений и обе версии читаются из одного accepted envelope.
 * Временные Category B sampling rules читаются из отдельного carrier до их удаления в P6.
 */
public final class ReadAppliedStateHandler {

    private final AcceptedPolicyAccess acceptedPolicy;

    public ReadAppliedStateHandler(AcceptedPolicyAccess acceptedPolicy) {
        this.acceptedPolicy = Objects.requireNonNull(acceptedPolicy, "acceptedPolicy");
    }

    public Map<String, Object> read() {
        AcceptedPolicyView accepted = acceptedPolicy.current();
        SamplingPolicySnapshot startupRules = acceptedPolicy.currentLegacySamplingRules();
        Map<String, Object> map = new LinkedHashMap<>();

        map.put(TracingControlProtocolKeys.SAMPLING_RATIO, accepted.samplingRatio());
        map.put(
                TracingControlProtocolKeys.SAMPLING_KILL_SWITCH_ENABLED,
                !accepted.samplingEnabled());
        map.put(
                TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS,
                accepted.samplingRouteRatios());
        map.put(
                TracingControlProtocolKeys.SAMPLING_DROP_PATH_PREFIXES,
                Collections.unmodifiableList(startupRules.getDroppedRoutes()));
        map.put(
                TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_VALUES,
                List.copyOf(startupRules.getForceRecordValues()));

        map.put(TracingControlProtocolKeys.VALIDATION_ENABLED, accepted.validationEnabled());
        map.put(TracingControlProtocolKeys.VALIDATION_STRICT, accepted.validationStrict());
        map.put(
                TracingControlProtocolKeys.VALIDATION_MODE,
                accepted.validationStrict() ? "STRICT" : "LOG_ONLY");

        map.put("_meta.samplingConfigVersion", accepted.acceptedSequence());
        map.put("_meta.samplingConfigSource", "accepted-state");
        map.put("_meta.validationConfigVersion", accepted.acceptedSequence());
        map.put("_meta.validationConfigSource", "accepted-state");

        return Collections.unmodifiableMap(map);
    }
}
