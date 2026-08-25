package space.br1440.platform.tracing.autoconfigure.control.settings.read;

import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;

public record AcceptedSettingsWithSequence(long acceptedSequence,
                                           String runtimeInstanceId,
                                           CompleteTracingSettings snapshot) {

    public ExpectedSettingsRevision expectedBase() {
        return new ExpectedSettingsRevision(runtimeInstanceId, acceptedSequence);
    }

}
