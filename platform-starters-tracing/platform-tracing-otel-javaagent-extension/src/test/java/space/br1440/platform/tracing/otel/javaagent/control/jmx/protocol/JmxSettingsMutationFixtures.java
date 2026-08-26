package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularDataSupport;

import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsDigest;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;

final class JmxSettingsMutationFixtures {

    private JmxSettingsMutationFixtures() {
    }

    static TracingSettingsStore store() {
        return new TracingSettingsStore(UUID.randomUUID().toString(), settings(1.0d));
    }

    static TracingSettings settings(double ratio) {
        return TracingSettings.of(
                true,
                ratio,
                Map.of("/orders", 0.5d),
                true,
                false,
                true,
                true,
                TracingLogLevel.INFO);
    }

    static CompositeData request(
            String runtimeId,
            long sequence,
            TracingSettings policy,
            String attemptId
    ) throws OpenDataException {
        TabularDataSupport routes = new TabularDataSupport(GeneratedJmxOpenTypes.ROUTE_RATIOS);
        for (Map.Entry<String, Double> route : policy.samplingRouteRatios().entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(GeneratedJmxWireContract.FIELD_ROUTE_RATIO_ROUTE_PREFIX, route.getKey());
            row.put(GeneratedJmxWireContract.FIELD_ROUTE_RATIO_RATIO, route.getValue());
            routes.put(new CompositeDataSupport(GeneratedJmxOpenTypes.ROUTE_RATIO, row));
        }

        Map<String, Object> policyFields = new LinkedHashMap<>();
        policyFields.put(
                GeneratedJmxWireContract.FIELD_RUNTIME_POLICY_DIAGNOSTICS_LOG_LEVEL,
                policy.diagnosticsLogLevel().name());
        policyFields.put(
                GeneratedJmxWireContract.FIELD_RUNTIME_POLICY_EXPORTER_ENABLED,
                policy.exporterEnabled());
        policyFields.put(
                GeneratedJmxWireContract.FIELD_RUNTIME_POLICY_SAMPLING_ENABLED,
                policy.samplingEnabled());
        policyFields.put(
                GeneratedJmxWireContract.FIELD_RUNTIME_POLICY_SAMPLING_RATIO,
                policy.samplingRatio());
        policyFields.put(
                GeneratedJmxWireContract.FIELD_RUNTIME_POLICY_SAMPLING_ROUTE_RATIOS,
                routes);
        policyFields.put(
                GeneratedJmxWireContract.FIELD_RUNTIME_POLICY_TRACE_CONTROL_HEADERS_ENABLED,
                policy.traceControlHeadersEnabled());
        policyFields.put(
                GeneratedJmxWireContract.FIELD_RUNTIME_POLICY_VALIDATION_ENABLED,
                policy.validationEnabled());
        policyFields.put(
                GeneratedJmxWireContract.FIELD_RUNTIME_POLICY_VALIDATION_STRICT,
                policy.validationStrict());
        CompositeData policyData = new CompositeDataSupport(
                GeneratedJmxOpenTypes.RUNTIME_POLICY,
                policyFields);

        Map<String, Object> requestFields = new LinkedHashMap<>();
        requestFields.put(
                GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_APPLY_TRIGGER,
                "REFRESH");
        requestFields.put(
                GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_ATTEMPT_ID,
                attemptId);
        requestFields.put(
                GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_CANDIDATE_DIGEST,
                TracingSettingsDigest.sha256(policy));
        requestFields.put(
                GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_EXPECTED_ACCEPTED_SEQUENCE,
                sequence);
        requestFields.put(
                GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_EXPECTED_RUNTIME_INSTANCE_ID,
                runtimeId);
        requestFields.put(
                GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_PROTOCOL_VERSION,
                GeneratedJmxWireContract.SCHEMA_MAJOR);
        requestFields.put(
                GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_RUNTIME_POLICY,
                policyData);
        requestFields.put(
                GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_SOURCE_AUTHORITY,
                "CONFIG_SERVER");
        requestFields.put(
                GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_SOURCE_REVISION,
                "revision-1");
        return new CompositeDataSupport(GeneratedJmxOpenTypes.APPLY_SNAPSHOT_REQUEST, requestFields);
    }

    static CompositeData withCandidateDigest(
            CompositeData request,
            String candidateDigest
    ) throws OpenDataException {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String field : request.getCompositeType().keySet()) {
            fields.put(field, request.get(field));
        }
        fields.put(
                GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_CANDIDATE_DIGEST,
                candidateDigest);
        return new CompositeDataSupport(request.getCompositeType(), fields);
    }

    static CompositeData malformedRequest() throws OpenDataException {
        CompositeType type = new CompositeType(
                "MalformedApplySnapshotRequest",
                "Malformed applySnapshot request",
                new String[]{GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_PROTOCOL_VERSION},
                new String[]{GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_PROTOCOL_VERSION},
                new OpenType<?>[]{SimpleType.INTEGER});
        return new CompositeDataSupport(
                type,
                new String[]{GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_PROTOCOL_VERSION},
                new Object[]{GeneratedJmxWireContract.SCHEMA_MAJOR});
    }
}
