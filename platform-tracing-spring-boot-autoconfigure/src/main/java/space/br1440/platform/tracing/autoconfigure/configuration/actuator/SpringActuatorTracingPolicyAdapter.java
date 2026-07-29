package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedSnapshotWithSequence;
import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedTracingPolicySnapshotReader;
import space.br1440.platform.tracing.autoconfigure.configuration.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicySnapshotClient;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicySource;

import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Actuator adapter: read-copy-change-validate-apply.
 * <p>
 * Reads the <strong>complete internal snapshot</strong> (including route ratios)
 * via {@link AcceptedTracingPolicySnapshotReader}, not the secret-safe projection.
 * This ensures single-leaf mutations preserve all other leaves including route ratios.
 * <p>
 * Contract:
 * - No retry on STALE (caller may retry if appropriate).
 * - No patch apply, no generic property mutation.
 * - Does not own accepted state.
 * - Does not call legacy JMX domain setters.
 */
public final class SpringActuatorTracingPolicyAdapter {

    // Exact mirror of Agent EffectiveTracingPolicyValidator.MAX_ROUTE_ENTRIES /
    // MAX_ROUTE_PREFIX_LENGTH (PrivateControlOpenTypeCodec, Agent module). These were
    // previously 64 / 256 here — a wire-parity defect: a submission of 65-100 routes, or a
    // 256-character key, would pass this pre-check yet be rejected by the real Agent bound
    // (100 entries, 255 UTF-16 code units), or vice versa. MAX_ROUTE_KEY_LENGTH is a
    // String#length() bound (UTF-16 code units), matching the Agent's own semantics.
    private static final int MAX_ROUTE_RATIOS = 100;
    private static final int MAX_ROUTE_KEY_LENGTH = 255;

    private static final java.util.Set<String> VALID_LOG_LEVELS =
            java.util.Set.of("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE");

    private final AcceptedTracingPolicySnapshotReader snapshotReader;
    private final TracingPolicySnapshotClient snapshotClient;

    public SpringActuatorTracingPolicyAdapter(
            AcceptedTracingPolicySnapshotReader snapshotReader,
            TracingPolicySnapshotClient snapshotClient) {
        this.snapshotReader = snapshotReader;
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

    /**
     * Read complete accepted snapshot (including route ratios), copy, apply change, validate, apply.
     * No retry on STALE.
     */
    private TracingPolicyDeliveryResult applyChange(UnaryOperator<CompleteTracingPolicySnapshot> change) {
        Optional<AcceptedSnapshotWithSequence> current = snapshotReader.currentComplete();
        if (current.isEmpty()) {
            return TracingPolicyDeliveryResult.FAILED;
        }
        AcceptedSnapshotWithSequence accepted = current.get();
        long expectedSequence = accepted.acceptedSequence();

        // Produce candidate: copy existing complete snapshot, apply one change
        // Route ratios are carried from existing snapshot, not discarded
        CompleteTracingPolicySnapshot candidate = change.apply(accepted.snapshot());

        // Guard: validate mutated candidate
        if (candidate.samplingRatio() < 0.0 || candidate.samplingRatio() > 1.0) {
            return TracingPolicyDeliveryResult.REJECTED;
        }
        if (!VALID_LOG_LEVELS.contains(candidate.diagnosticsLogLevel().toUpperCase())) {
            return TracingPolicyDeliveryResult.REJECTED;
        }

        return snapshotClient.applySnapshot(expectedSequence, candidate, TracingPolicySource.ACTUATOR);
    }
}
