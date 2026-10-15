package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.TabularDataSupport;

import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class PlatformJmxRequestEncoder {

    public static CompositeData encodeApplyRequest(ApplySnapshotRequestParameters parameters) {
        try {
            CompositeData runtimePolicy = encodeRuntimePolicy(parameters.policy());

            Map<String, Object> requestFields = new LinkedHashMap<>();
            requestFields.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_ATTEMPT_ID, parameters.attemptId());
            requestFields.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_EXPECTED_ACCEPTED_SEQUENCE, parameters.expectedBase().acceptedSequence());
            requestFields.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_EXPECTED_AGENT_INSTANCE_ID, parameters.expectedBase().agentInstanceId());
            requestFields.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_RUNTIME_POLICY, runtimePolicy);
            requestFields.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_WRITER, parameters.writer());

            return new CompositeDataSupport(JmxOpenTypes.APPLY_SNAPSHOT_REQUEST, requestFields);
        } catch (OpenDataException exception) {
            throw new JmxRequestEncodingException("APPLY_SNAPSHOT_REQUEST", exception);
        }
    }

    private static CompositeData encodeRuntimePolicy(CompleteTracingSettings policy) throws OpenDataException {
        TabularDataSupport routeRatios = encodeRouteRatios(policy.samplingRouteRatios());

        Map<String, Object> policyFields = new LinkedHashMap<>();
        policyFields.put(JmxWireContract.FIELD_RUNTIME_POLICY_DIAGNOSTICS_LOG_LEVEL, policy.diagnosticsLogLevel());
        policyFields.put(JmxWireContract.FIELD_RUNTIME_POLICY_EXPORTER_ENABLED, policy.exporterEnabled());
        policyFields.put(JmxWireContract.FIELD_RUNTIME_POLICY_SAMPLING_ENABLED, policy.samplingEnabled());
        policyFields.put(JmxWireContract.FIELD_RUNTIME_POLICY_SAMPLING_RATIO, policy.samplingRatio());
        policyFields.put(JmxWireContract.FIELD_RUNTIME_POLICY_SAMPLING_ROUTE_RATIOS, routeRatios);
        policyFields.put(JmxWireContract.FIELD_RUNTIME_POLICY_TRACE_CONTROL_HEADERS_ENABLED, policy.traceControlHeadersEnabled());
        policyFields.put(JmxWireContract.FIELD_RUNTIME_POLICY_VALIDATION_LEVEL, policy.validationLevel());

        return new CompositeDataSupport(JmxOpenTypes.RUNTIME_POLICY, policyFields);
    }

    private static TabularDataSupport encodeRouteRatios(Map<String, Double> routeRatios) throws OpenDataException {
        TabularDataSupport table = new TabularDataSupport(JmxOpenTypes.ROUTE_RATIOS);
        for (Map.Entry<String, Double> route : new TreeMap<>(routeRatios).entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(JmxWireContract.FIELD_ROUTE_RATIO_ROUTE_PREFIX, route.getKey());
            row.put(JmxWireContract.FIELD_ROUTE_RATIO_RATIO, route.getValue());
            table.put(new CompositeDataSupport(JmxOpenTypes.ROUTE_RATIO, row));
        }

        return table;
    }
}
