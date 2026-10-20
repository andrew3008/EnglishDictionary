package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularDataSupport;

import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

import lombok.experimental.UtilityClass;

@UtilityClass
final class JmxSettingsMutationFixtures {

    static TracingSettingsStore store() {
        return AcceptedPolicyTestFixtures.bootstrapAgentDefaultsStore(
                AgentInstanceId.parse("00000000-0000-0000-0000-000000000001"),
                settings(1.0d)
        );
    }

    static TracingSettings settings(double ratio) {
        return TracingSettings.of(
                true,
                ratio,
                Map.of("/orders", 0.5d), SpanAttributeValidationLevel.WARNING, true, true, AgentLogLevel.INFO);
    }

    static CompositeData request(String agentInstanceId,
                                 long sequence,
                                 TracingSettings policy,
                                 String attemptId) throws OpenDataException {
        return request(agentInstanceId, sequence, policy, attemptId, "CONFIG_SERVER");
    }

    static CompositeData request(
            String agentInstanceId,
            long sequence,
            TracingSettings policy,
            String attemptId,
            String writer
    ) throws OpenDataException {
        TabularDataSupport routes = new TabularDataSupport(JmxOpenTypes.ROUTE_RATIOS);
        for (Map.Entry<String, Double> route : policy.samplingRouteRatios().entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(JmxWireContract.FIELD_ROUTE_RATIO_ROUTE_PREFIX, route.getKey());
            row.put(JmxWireContract.FIELD_ROUTE_RATIO_RATIO, route.getValue());
            routes.put(new CompositeDataSupport(JmxOpenTypes.ROUTE_RATIO, row));
        }

        Map<String, Object> policyFields = new LinkedHashMap<>();
        policyFields.put(
                JmxWireContract.FIELD_RUNTIME_POLICY_DIAGNOSTICS_LOG_LEVEL,
                policy.diagnosticsLogLevel().name());
        policyFields.put(
                JmxWireContract.FIELD_RUNTIME_POLICY_EXPORTER_ENABLED,
                policy.exporterEnabled());
        policyFields.put(
                JmxWireContract.FIELD_RUNTIME_POLICY_SAMPLING_ENABLED,
                policy.samplingEnabled());
        policyFields.put(
                JmxWireContract.FIELD_RUNTIME_POLICY_SAMPLING_RATIO,
                policy.samplingRatio());
        policyFields.put(
                JmxWireContract.FIELD_RUNTIME_POLICY_SAMPLING_ROUTE_RATIOS,
                routes);
        policyFields.put(
                JmxWireContract.FIELD_RUNTIME_POLICY_TRACE_CONTROL_HEADERS_ENABLED,
                policy.traceControlHeadersEnabled());
        policyFields.put(
                JmxWireContract.FIELD_RUNTIME_POLICY_VALIDATION_LEVEL,
                policy.validationLevel().name());
        CompositeData policyData = new CompositeDataSupport(
                JmxOpenTypes.RUNTIME_POLICY,
                policyFields);

        Map<String, Object> requestFields = new LinkedHashMap<>();
        requestFields.put(
                JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_ATTEMPT_ID,
                attemptId);
        requestFields.put(
                JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_EXPECTED_ACCEPTED_SEQUENCE,
                sequence);
        requestFields.put(
                JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_EXPECTED_AGENT_INSTANCE_ID,
                agentInstanceId);
        requestFields.put(
                JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_RUNTIME_POLICY,
                policyData);
        requestFields.put(
                JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_WRITER,
                writer);
        return new CompositeDataSupport(JmxOpenTypes.APPLY_SNAPSHOT_REQUEST, requestFields);
    }

    static CompositeData malformedRequest() throws OpenDataException {
        CompositeType type = new CompositeType(
                "MalformedApplySnapshotRequest",
                "Malformed applySnapshot request",
                new String[]{JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_ATTEMPT_ID},
                new String[]{JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_ATTEMPT_ID},
                new OpenType<?>[]{SimpleType.STRING}
        );

        return new CompositeDataSupport(
                type,
                new String[]{JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_ATTEMPT_ID},
                new Object[]{"malformed"}
        );
    }
}
