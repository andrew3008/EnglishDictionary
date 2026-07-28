package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

import space.br1440.platform.tracing.autoconfigure.diagnostics.OtelAgentLogLevel;

final class SpringActuatorTracingPolicyAdapter {

    private final AcceptedTracingPolicyReader acceptedReader;
    private final TracingPolicySnapshotClient snapshotClient;
    private final TracingPolicyDeliveryDiagnostics diagnostics;

    SpringActuatorTracingPolicyAdapter(
            AcceptedTracingPolicyReader acceptedReader,
            TracingPolicySnapshotClient snapshotClient,
            TracingPolicyDeliveryDiagnostics diagnostics) {
        this.acceptedReader = Objects.requireNonNull(acceptedReader, "acceptedReader");
        this.snapshotClient = Objects.requireNonNull(snapshotClient, "snapshotClient");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    TracingPolicyDeliveryResult setSamplingEnabled(boolean enabled) {
        return applyChange(policy -> policy.withSamplingEnabled(enabled));
    }

    TracingPolicyDeliveryResult setSamplingRatio(double ratio) {
        return applyChange(policy -> policy.withSamplingRatio(ratio));
    }

    TracingPolicyDeliveryResult replaceSamplingRouteRatios(Map<String, Double> routeRatios) {
        Objects.requireNonNull(routeRatios, "routeRatios");
        return applyChange(policy -> policy.withSamplingRouteRatios(routeRatios));
    }

    TracingPolicyDeliveryResult setValidationEnabled(boolean enabled) {
        return applyChange(policy -> policy.withValidationEnabled(enabled));
    }

    TracingPolicyDeliveryResult setValidationStrict(boolean strict) {
        return applyChange(policy -> policy.withValidationStrict(strict));
    }

    TracingPolicyDeliveryResult setExporterEnabled(boolean enabled) {
        return applyChange(policy -> policy.withExporterEnabled(enabled));
    }

    TracingPolicyDeliveryResult setTraceControlHeadersEnabled(boolean enabled) {
        return applyChange(policy -> policy.withTraceControlHeadersEnabled(enabled));
    }

    TracingPolicyDeliveryResult setDiagnosticsLogLevel(OtelAgentLogLevel logLevel) {
        Objects.requireNonNull(logLevel, "logLevel");
        return applyChange(policy -> policy.withDiagnosticsLogLevel(logLevel));
    }

    private TracingPolicyDeliveryResult applyChange(
            UnaryOperator<CompleteTracingPolicySnapshot> change) {
        AcceptedTracingPolicyView accepted = acceptedReader.current();
        CompleteTracingPolicySnapshot candidate = Objects.requireNonNull(
                change.apply(accepted.policy()),
                "candidate");
        TracingPolicyDeliveryResult result = snapshotClient.applySnapshot(
                ExpectedAcceptedState.from(accepted),
                candidate,
                TracingPolicySource.ACTUATOR,
                TracingPolicyApplyTrigger.ACTUATOR,
                "");
        diagnostics.record(TracingPolicySource.ACTUATOR, "", result);
        return result;
    }
}
