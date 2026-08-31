package space.br1440.platform.tracing.autoconfigure.control.settings.read;

import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;

public record AcceptedSettingsReadModel(ExpectedSettingsRevision acceptedRevision,
                                        AcceptedPolicySummary summary) {
}
