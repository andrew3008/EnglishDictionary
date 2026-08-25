package space.br1440.platform.tracing.autoconfigure.control.settings.read;

public record AcceptedSettingsReadModel(AgentReadinessSnapshot agentReadiness,
                                       AcceptedSettingsLeaves leaves) {
}
