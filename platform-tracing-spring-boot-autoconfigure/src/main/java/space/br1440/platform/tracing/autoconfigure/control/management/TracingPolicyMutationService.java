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
import java.util.function.UnaryOperator;

/**
 * Actuator write-path: читает принятый snapshot, строит candidate через {@code with*} и
 * доставляет его в Agent. Value-invariants принадлежат {@link CompleteTracingPolicySnapshot};
 * этот сервис лишь переводит {@link IllegalArgumentException} при создании candidate в
 * {@link TracingPolicyDeliveryResult#REJECTED} и не делает retry/rebase.
 */
@RequiredArgsConstructor
public final class TracingPolicyMutationService {

    private final CompleteTracingPolicyReader snapshotReader;
    private final TracingPolicySnapshotApplier snapshotClient;

    public TracingPolicyDeliveryResult setSamplingEnabled(boolean value) {
        return applyChange(s -> s.withSamplingEnabled(value));
    }

    public TracingPolicyDeliveryResult setSamplingRatio(double value) {
        return applyChange(s -> s.withSamplingRatio(value));
    }

    public TracingPolicyDeliveryResult replaceSamplingRouteRatios(Map<String, Double> routeRatios) {
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
        return applyChange(s -> s.withDiagnosticsLogLevel(level));
    }

    private TracingPolicyDeliveryResult applyChange(UnaryOperator<CompleteTracingPolicySnapshot> change) {
        Optional<AcceptedSnapshotWithSequence> current = snapshotReader.currentComplete();
        if (current.isEmpty()) {
            return TracingPolicyDeliveryResult.FAILED;
        }

        AcceptedSnapshotWithSequence accepted = current.get();

        CompleteTracingPolicySnapshot candidate;
        try {
            // Ловим только value-invariant сбои при материализации candidate.
            // Исключения reader/applier сюда не входят и не маскируются.
            candidate = change.apply(accepted.snapshot());
        } catch (IllegalArgumentException rejected) {
            return TracingPolicyDeliveryResult.REJECTED;
        }

        return snapshotClient.applySnapshot(accepted.expectedBase(), candidate, TracingPolicySource.ACTUATOR);
    }
}
