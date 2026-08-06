package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import lombok.experimental.UtilityClass;

import space.br1440.platform.tracing.autoconfigure.control.jmx.PrivateControlLocator;
import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.control.policy.ExpectedTracingPolicyBase;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.TabularDataSupport;
import java.util.Map;
import java.util.TreeMap;

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
                routes.put(
                        new CompositeDataSupport(
                                PrivateControlWireTypes.ROUTE_ROW,
                                new String[]{"ratio", "routePrefix"},
                                new Object[]{route.getValue(), route.getKey()}
                        )
                );
            }

            CompositeData policy = new CompositeDataSupport(
                    PrivateControlWireTypes.POLICY,

                    new String[]{
                            "diagnosticsLogLevel",
                            "exporterEnabled",
                            "samplingEnabled",
                            "samplingRatio",
                            "samplingRouteRatios",
                            "traceControlHeadersEnabled",
                            "validationEnabled",
                            "validationStrict"
                    },

                    new Object[]{
                            snapshot.diagnosticsLogLevel(),
                            snapshot.exporterEnabled(),
                            snapshot.samplingEnabled(),
                            snapshot.samplingRatio(),
                            routes,
                            snapshot.traceControlHeadersEnabled(),
                            snapshot.validationEnabled(),
                            snapshot.validationStrict()
                    });

            return new CompositeDataSupport(
                    PrivateControlWireTypes.REQUEST,
                    PrivateControlWireTypes.REQUEST_KEYS,
                    new Object[]{
                            snapshot.applyTrigger(),
                            snapshot.attemptId(),
                            snapshot.candidateDigest(),
                            snapshot.expectedAcceptedSequence(),
                            snapshot.expectedRuntimeInstanceId(),
                            PrivateControlLocator.PROTOCOL_VERSION,
                            policy,
                            snapshot.sourceAuthority(),
                            snapshot.sourceRevision()
                    });
        } catch (OpenDataException exception) {
            throw new IllegalArgumentException("Cannot encode private control request", exception);
        }
    }
}
