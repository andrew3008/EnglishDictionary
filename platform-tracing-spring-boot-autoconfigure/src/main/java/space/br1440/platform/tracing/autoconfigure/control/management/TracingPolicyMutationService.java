package space.br1440.platform.tracing.autoconfigure.control.management;

import lombok.RequiredArgsConstructor;

import space.br1440.platform.tracing.autoconfigure.control.policy.read.AcceptedSnapshotWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.policy.read.CompleteTracingPolicyReader;
import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicyDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicySnapshotApplier;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicySource;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

@RequiredArgsConstructor
public final class TracingPolicyMutationService {

    private static final int MAX_ROUTE_RATIOS = 100;
    private static final int MAX_ROUTE_KEY_LENGTH = 255;
    private static final Set<String> VALID_LOG_LEVELS = Set.of("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE");

    private final CompleteTracingPolicyReader snapshotReader;
    private final TracingPolicySnapshotApplier snapshotClient;

    public TracingPolicyDeliveryResult setSamplingEnabled(boolean value) {
        return applyChange(s -> s.withSamplingEnabled(value));
    }

    public TracingPolicyDeliveryResult setSamplingRatio(double value) {
        if (value < 0.0 || value > 1.0) {
            return TracingPolicyDeliveryResult.REJECTED;
        }

        return applyChange(s -> s.withSamplingRatio(value));
    }

    public TracingPolicyDeliveryResult replaceSamplingRouteRatios(Map<String, Double> routeRatios) {
        if (routeRatios == null) {
            return TracingPolicyDeliveryResult.REJECTED;
        }

        if (routeRatios.size() > MAX_ROUTE_RATIOS) {
            return TracingPolicyDeliveryResult.REJECTED;
        }

        for (Map.Entry<String, Double> entry : routeRatios.entrySet()) {
            if (entry.getKey() == null || entry.getKey().length() > MAX_ROUTE_KEY_LENGTH) {
                return TracingPolicyDeliveryResult.REJECTED;
            }

            if (entry.getValue() == null || entry.getValue() < 0.0 || entry.getValue() > 1.0) {
                return TracingPolicyDeliveryResult.REJECTED;
            }
        }

        long uniqueCount = routeRatios.keySet().stream()
                .map(String::toLowerCase)
                .distinct()
                .count();

        if (uniqueCount != routeRatios.size()) {
            return TracingPolicyDeliveryResult.REJECTED;
        }

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

    private TracingPolicyDeliveryResult applyChange(UnaryOperator<CompleteTracingPolicySnapshot> change) {
        Optional<AcceptedSnapshotWithSequence> current = snapshotReader.currentComplete();
        if (current.isEmpty()) {
            return TracingPolicyDeliveryResult.FAILED;
        }

        AcceptedSnapshotWithSequence accepted = current.get();
        long expectedSequence = accepted.acceptedSequence();

        CompleteTracingPolicySnapshot candidate = change.apply(accepted.snapshot());
        if (candidate.samplingRatio() < 0.0 || candidate.samplingRatio() > 1.0) {
            return TracingPolicyDeliveryResult.REJECTED;
        }

        if (!VALID_LOG_LEVELS.contains(candidate.diagnosticsLogLevel().toUpperCase())) {
            return TracingPolicyDeliveryResult.REJECTED;
        }

        return snapshotClient.applySnapshot(expectedSequence, candidate, TracingPolicySource.ACTUATOR);
    }
}
