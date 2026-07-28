package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedTracingPolicyView;
import space.br1440.platform.tracing.autoconfigure.configuration.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicySnapshotClient;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicySource;

import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Actuator authority adapter. Orchestrates read-copy-change-validate-apply
 * for typed Category A leaf mutations.
 * <p>
 * Does NOT own accepted state.
 * Does NOT change Spring properties.
 * Does NOT call RuntimeConfigApplier.
 * Does NOT call legacy domain JMX setters.
 * Does NOT retry on STALE.
 * No patch apply. No generic property mutation.
 */
public final class SpringActuatorTracingPolicyAdapter {

    private static final int MAX_ROUTE_RATIOS = 64;
    private static final int MAX_ROUTE_KEY_LENGTH = 256;

    private final TracingPolicySnapshotClient snapshotClient;

    public SpringActuatorTracingPolicyAdapter(TracingPolicySnapshotClient snapshotClient) {
        this.snapshotClient = snapshotClient;
    }

    public TracingPolicyDeliveryResult setSamplingEnabled(boolean value) {
        return applyChange(s -> s.withSamplingEnabled(value));
    }

    public TracingPolicyDeliveryResult setSamplingRatio(double value) {
        if (value < 0.0 || value > 1.0) return TracingPolicyDeliveryResult.REJECTED;
        return applyChange(s -> s.withSamplingRatio(value));
    }

    public TracingPolicyDeliveryResult replaceSamplingRouteRatios(Map<String, Double> routeRatios) {
        if (routeRatios == null) return TracingPolicyDeliveryResult.REJECTED;
        if (routeRatios.size() > MAX_ROUTE_RATIOS) return TracingPolicyDeliveryResult.REJECTED;
        for (Map.Entry<String, Double> entry : routeRatios.entrySet()) {
            if (entry.getKey() == null || entry.getKey().length() > MAX_ROUTE_KEY_LENGTH)
                return TracingPolicyDeliveryResult.REJECTED;
            if (entry.getValue() == null || entry.getValue() < 0.0 || entry.getValue() > 1.0)
                return TracingPolicyDeliveryResult.REJECTED;
        }
        // Check for duplicate normalized routes
        long uniqueCount = routeRatios.keySet().stream().map(String::toLowerCase).distinct().count();
        if (uniqueCount != routeRatios.size()) return TracingPolicyDeliveryResult.REJECTED;
        return applyChange(s -> s.withSamplingRouteRatios(routeRatios));
    }

    public TracingPolicyDeliveryResult setValidationEnabled(boolean value) {
        return applyChange(s -> s.withValidationEnabled(value));
    }

    public TracingPolicyDeliveryResult setValidationStrict(boolean value) {
        return applyChange(s -> s.withValidationStrict(value));
    }

    public TracingPolicyDeliveryResult setExporterEnabled(boolean value) {
        return applyChange(s -> s.withExporterEnabled(value));
    }

    public TracingPolicyDeliveryResult setTraceControlHeadersEnabled(boolean value) {
        return applyChange(s -> s.withTraceControlHeadersEnabled(value));
    }

    public TracingPolicyDeliveryResult setDiagnosticsLogLevel(String level) {
        if (level == null || !VALID_LOG_LEVELS.contains(level.toUpperCase())) {
            return TracingPolicyDeliveryResult.REJECTED;
        }
        return applyChange(s -> s.withDiagnosticsLogLevel(level.toUpperCase()));
    }

    private static final java.util.Set<String> VALID_LOG_LEVELS =
            java.util.Set.of("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE");

    /**
     * Core read-copy-change-validate-apply helper.
     * Reads current accepted state, copies, applies one typed change, validates, delegates.
     * No retry on STALE.
     */
    private TracingPolicyDeliveryResult applyChange(UnaryOperator<CompleteTracingPolicySnapshot> change) {
        Optional<AcceptedTracingPolicyView> currentView = snapshotClient.current();
        if (currentView.isEmpty()) {
            return TracingPolicyDeliveryResult.FAILED;
        }
        AcceptedTracingPolicyView view = currentView.get();
        long expectedSequence = view.acceptedSequence();

        // Reconstruct snapshot from view for the copy base
        // (view is secret-safe projection; route ratios not in view → keep existing)
        // We apply the change to a baseline built from what we can observe.
        // NOTE: If the accepted projection does not carry full route ratios (by design,
        // since route ratios are not in the secret-safe view), the change operator
        // for replaceSamplingRouteRatios will override them fully (whole-map replacement).
        CompleteTracingPolicySnapshot base = CompleteTracingPolicySnapshot.builder()
                .samplingEnabled(view.samplingEnabled())
                .samplingRatio(view.samplingRatio())
                .samplingRouteRatios(Map.of()) // route ratios replaced by the change if applicable
                .validationEnabled(view.validationEnabled())
                .validationStrict(view.validationStrict())
                .exporterEnabled(view.exporterEnabled())
                .traceControlHeadersEnabled(view.traceControlHeadersEnabled())
                .diagnosticsLogLevel(view.diagnosticsLogLevel())
                .build();

        CompleteTracingPolicySnapshot candidate = change.apply(base);

        // Validate ratio bounds
        if (candidate.samplingRatio() < 0.0 || candidate.samplingRatio() > 1.0) {
            return TracingPolicyDeliveryResult.REJECTED;
        }
        if (!VALID_LOG_LEVELS.contains(candidate.diagnosticsLogLevel().toUpperCase())) {
            return TracingPolicyDeliveryResult.REJECTED;
        }

        return snapshotClient.applySnapshot(expectedSequence, candidate, TracingPolicySource.ACTUATOR);
    }
}
