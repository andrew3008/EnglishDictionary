package space.br1440.platform.tracing.autoconfigure.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.OtelAgentLogLevel;

class PrivateControlTracingPolicyClientFailureSemanticsTest {

    @Test
    void unavailableControlPlaneBecomesTypedDeliveryFailure() {
        PrivateControlTracingPolicyClient client = new PrivateControlTracingPolicyClient();

        TracingPolicyDeliveryResult result = client.applySnapshot(
                new ExpectedAcceptedState(UUID.randomUUID().toString(), 7L),
                policy(),
                TracingPolicySource.ACTUATOR,
                TracingPolicyApplyTrigger.ACTUATOR,
                "");

        assertThat(result.outcome()).isEqualTo(TracingPolicyDeliveryOutcome.FAILED);
        assertThat(result.messageCode()).isEqualTo("CONTROL_PLANE_UNAVAILABLE");
        assertThat(result.acceptedSequence()).isEqualTo(7L);
    }

    private static CompleteTracingPolicySnapshot policy() {
        return new CompleteTracingPolicySnapshot(
                true,
                0.1d,
                Map.of(),
                true,
                false,
                true,
                true,
                OtelAgentLogLevel.INFO);
    }
}
