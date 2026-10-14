package space.br1440.platform.tracing.autoconfigure.control.management;

import java.util.Map;
import java.util.function.UnaryOperator;

import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsWriter;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsDelivery;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsWriter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public final class TracingSettingsMutationService {

    private final TracingSettingsWriter settingsWriter;

    public SettingsDelivery setSamplingEnabled(boolean value) {
        return applyChange(s -> s.withSamplingEnabled(value));
    }

    public SettingsDelivery setSamplingRatio(double value) {
        return applyChange(s -> s.withSamplingRatio(value));
    }

    public SettingsDelivery replaceSamplingRouteRatios(Map<String, Double> routeRatios) {
        return applyChange(s -> s.withSamplingRouteRatios(routeRatios));
    }

    public SettingsDelivery setValidationLevel(String value) {
        return applyChange(s -> s.withValidationLevel(value));
    }

    public SettingsDelivery setExporterEnabled(boolean value) {
        return applyChange(s -> s.withExporterEnabled(value));
    }

    public SettingsDelivery setTraceControlHeadersEnabled(boolean value) {
        return applyChange(s -> s.withTraceControlHeadersEnabled(value));
    }

    public SettingsDelivery setDiagnosticsLogLevel(String level) {
        return applyChange(s -> s.withDiagnosticsLogLevel(level));
    }

    private SettingsDelivery applyChange(UnaryOperator<CompleteTracingSettings> change) {
        AcceptedSettings accepted = settingsWriter.current()
                .orElseThrow(() -> new TracingConfigurationGuardException("Accepted tracing settings are unavailable"));
        CompleteTracingSettings candidate = change.apply(accepted.policy());

        SettingsDelivery delivery = settingsWriter.applySnapshot(
                accepted.revision(),
                candidate,
                SettingsWriter.ACTUATOR
        );
        log.info(
                "Actuator tracing settings mutation completed: attemptId={}, writer={}, outcome={}, messageCode={}",
                delivery.attemptId(),
                SettingsWriter.ACTUATOR,
                delivery.outcome(),
                delivery.messageCode()
        );
        return delivery;
    }
}
