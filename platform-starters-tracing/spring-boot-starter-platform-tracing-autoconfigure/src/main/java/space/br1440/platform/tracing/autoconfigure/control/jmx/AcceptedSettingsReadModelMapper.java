package space.br1440.platform.tracing.autoconfigure.control.jmx;

import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedPolicySummary;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsReadModel;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsWithSequence;

import lombok.experimental.UtilityClass;

@UtilityClass
final class AcceptedSettingsReadModelMapper {

    static AcceptedSettingsReadModel toReadModel(AcceptedSettingsWithSequence accepted) {
        CompleteTracingSettings snapshot = accepted.snapshot();
        AcceptedPolicySummary summary = new AcceptedPolicySummary(
                snapshot.samplingEnabled(),
                snapshot.samplingRatio(),
                snapshot.samplingRouteRatios().size(),
                snapshot.validationLevel(),
                snapshot.exporterEnabled(),
                snapshot.traceControlHeadersEnabled(),
                snapshot.diagnosticsLogLevel()
        );

        return new AcceptedSettingsReadModel(accepted.expectedBase(), summary);
    }
}
