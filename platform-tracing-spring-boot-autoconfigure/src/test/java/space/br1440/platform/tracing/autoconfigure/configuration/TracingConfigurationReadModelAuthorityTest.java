package space.br1440.platform.tracing.autoconfigure.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import space.br1440.platform.tracing.autoconfigure.diagnostics.OtelAgentLogLevel;

class TracingConfigurationReadModelAuthorityTest {

    @ParameterizedTest
    @EnumSource(TracingConfigurationAuthority.class)
    void exposesAcceptedAgentTruthForEveryAuthority(
            TracingConfigurationAuthority authority) {
        AcceptedTracingPolicyView accepted = accepted();
        TracingConfigurationReadiness readiness = new TracingConfigurationReadiness();
        readiness.recordAcceptedStateAvailable(accepted);
        TracingConfigurationReadModel model = new TracingConfigurationReadModel(
                authority,
                () -> accepted,
                readiness,
                new TracingPolicyDeliveryDiagnostics());

        assertThat(model.read())
                .containsEntry("status", "AVAILABLE")
                .containsEntry("authority", authority.name())
                .containsEntry("truthSource", "AGENT_ACCEPTED_STATE")
                .containsEntry("acceptedSequence", 12L);
    }

    private static AcceptedTracingPolicyView accepted() {
        return new AcceptedTracingPolicyView(
                UUID.randomUUID().toString(),
                12L,
                "a".repeat(64),
                new CompleteTracingPolicySnapshot(
                        true,
                        0.1d,
                        Map.of("/orders", 0.3d),
                        true,
                        false,
                        true,
                        true,
                        OtelAgentLogLevel.INFO),
                "APPLIED",
                UUID.randomUUID().toString(),
                "NONE",
                false,
                false);
    }
}
