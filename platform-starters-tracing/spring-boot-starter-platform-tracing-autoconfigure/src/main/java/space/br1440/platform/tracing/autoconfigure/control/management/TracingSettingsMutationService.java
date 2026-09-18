package space.br1440.platform.tracing.autoconfigure.control.management;

import lombok.RequiredArgsConstructor;

import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.CompleteTracingSettingsReader;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsApplier;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliverySource;

import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

@RequiredArgsConstructor
public final class TracingSettingsMutationService {

    private final CompleteTracingSettingsReader snapshotReader;
    private final TracingSettingsApplier snapshotClient;

    public TracingSettingsDeliveryResult setSamplingEnabled(boolean value) {
        return applyChange(s -> s.withSamplingEnabled(value));
    }

    public TracingSettingsDeliveryResult setSamplingRatio(double value) {
        return applyChange(s -> s.withSamplingRatio(value));
    }

    public TracingSettingsDeliveryResult replaceSamplingRouteRatios(Map<String, Double> routeRatios) {
        return applyChange(s -> s.withSamplingRouteRatios(routeRatios));
    }

    public TracingSettingsDeliveryResult setValidationLevel(String value) {
        return applyChange(s -> s.withValidationLevel(value));
    }

    public TracingSettingsDeliveryResult setExporterEnabled(boolean value) {
        return applyChange(s -> s.withExporterEnabled(value));
    }

    public TracingSettingsDeliveryResult setTraceControlHeadersEnabled(boolean value) {
        return applyChange(s -> s.withTraceControlHeadersEnabled(value));
    }

    public TracingSettingsDeliveryResult setDiagnosticsLogLevel(String level) {
        return applyChange(s -> s.withDiagnosticsLogLevel(level));
    }

    private TracingSettingsDeliveryResult applyChange(UnaryOperator<CompleteTracingSettings> change) {
        Optional<AcceptedSettingsWithSequence> current = snapshotReader.currentComplete();
        if (current.isEmpty()) {
            return TracingSettingsDeliveryResult.FAILED;
        }

        AcceptedSettingsWithSequence accepted = current.get();

        CompleteTracingSettings candidate;
        try {
            candidate = change.apply(accepted.snapshot());
        } catch (IllegalArgumentException rejected) {
            return TracingSettingsDeliveryResult.REJECTED;
        }

        return snapshotClient.applySnapshot(accepted.expectedBase(), candidate, TracingSettingsDeliverySource.ACTUATOR);
    }
}
