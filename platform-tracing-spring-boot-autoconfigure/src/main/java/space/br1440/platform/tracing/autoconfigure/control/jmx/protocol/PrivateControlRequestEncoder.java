package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.TabularDataSupport;

import space.br1440.platform.tracing.autoconfigure.control.jmx.PrivateControlLocator;
import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.PrivateControlWireTypes.WireField;
import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.control.policy.ExpectedTracingPolicyBase;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class PrivateControlRequestEncoder {

    public static CompositeData encodeApplyRequest(ExpectedTracingPolicyBase expectedBase,
                                                     String attemptId,
                                                     String sourceAuthority,
                                                     String applyTrigger,
                                                     String sourceRevision,
                                                     CompleteTracingPolicySnapshot policy) {
        String candidateDigest = CandidateDigest.sha256(policy);
        PrivateControlSnapshot snapshot = new PrivateControlSnapshot(
                expectedBase.runtimeInstanceId(),
                expectedBase.acceptedSequence(),
                attemptId,
                sourceAuthority,
                applyTrigger,
                sourceRevision,
                candidateDigest,
                policy.samplingEnabled(),
                policy.samplingRatio(),
                policy.samplingRouteRatios(),
                policy.validationEnabled(),
                policy.validationStrict(),
                policy.exporterEnabled(),
                policy.traceControlHeadersEnabled(),
                policy.diagnosticsLogLevel()
        );

        return encodeRequest(snapshot);
    }

    static CompositeData encodeRequest(PrivateControlSnapshot snapshot) {
        try {
            TabularDataSupport routes = new TabularDataSupport(PrivateControlWireTypes.ROUTES);
            for (Map.Entry<String, Double> route : new TreeMap<>(snapshot.samplingRouteRatios()).entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put(WireField.ROUTE_PREFIX.wireName(), route.getKey());
                row.put(WireField.ROUTE_RATIO.wireName(), route.getValue());
                routes.put(new CompositeDataSupport(PrivateControlWireTypes.ROUTE_ROW, row));
            }

            Map<String, Object> policyFields = new LinkedHashMap<>();
            policyFields.put(WireField.DIAGNOSTICS_LOG_LEVEL.wireName(), snapshot.diagnosticsLogLevel());
            policyFields.put(WireField.EXPORTER_ENABLED.wireName(), snapshot.exporterEnabled());
            policyFields.put(WireField.SAMPLING_ENABLED.wireName(), snapshot.samplingEnabled());
            policyFields.put(WireField.SAMPLING_RATIO.wireName(), snapshot.samplingRatio());
            policyFields.put(WireField.SAMPLING_ROUTE_RATIOS.wireName(), routes);
            policyFields.put(WireField.TRACE_CONTROL_HEADERS_ENABLED.wireName(), snapshot.traceControlHeadersEnabled());
            policyFields.put(WireField.VALIDATION_ENABLED.wireName(), snapshot.validationEnabled());
            policyFields.put(WireField.VALIDATION_STRICT.wireName(), snapshot.validationStrict());
            CompositeData policy = new CompositeDataSupport(PrivateControlWireTypes.POLICY, policyFields);

            Map<String, Object> requestFields = new LinkedHashMap<>();
            requestFields.put(WireField.APPLY_TRIGGER.wireName(), snapshot.applyTrigger());
            requestFields.put(WireField.ATTEMPT_ID.wireName(), snapshot.attemptId());
            requestFields.put(WireField.CANDIDATE_DIGEST.wireName(), snapshot.candidateDigest());
            requestFields.put(WireField.EXPECTED_ACCEPTED_SEQUENCE.wireName(), snapshot.expectedAcceptedSequence());
            requestFields.put(WireField.EXPECTED_RUNTIME_INSTANCE_ID.wireName(), snapshot.expectedRuntimeInstanceId());
            requestFields.put(WireField.PROTOCOL_VERSION.wireName(), PrivateControlLocator.PROTOCOL_VERSION);
            requestFields.put(WireField.RUNTIME_POLICY.wireName(), policy);
            requestFields.put(WireField.SOURCE_AUTHORITY.wireName(), snapshot.sourceAuthority());
            requestFields.put(WireField.SOURCE_REVISION.wireName(), snapshot.sourceRevision());

            return new CompositeDataSupport(PrivateControlWireTypes.REQUEST, requestFields);
        } catch (OpenDataException exception) {
            throw new IllegalArgumentException("Cannot encode private control request", exception);
        }
    }
}
