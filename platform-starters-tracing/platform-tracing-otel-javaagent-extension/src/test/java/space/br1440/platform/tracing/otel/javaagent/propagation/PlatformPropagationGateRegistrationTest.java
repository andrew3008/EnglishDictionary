package space.br1440.platform.tracing.otel.javaagent.propagation;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformPropagationGateRegistrationTest {

    @Test
    void bindCloseRestoresPreviousAndStaleCloseDoesNotClearNewerBinding() throws Exception {
        // given
        PlatformPropagationGate gate = new PlatformPropagationGate();
        TracingSettingsSource policyA = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.1d);
        TracingSettingsSource policyB = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.9d);

        AutoCloseable token1 = gate.bindAcceptedPolicy(policyA);
        assertThat(gate.isEnabled()).isEqualTo(policyA.current().traceControlHeadersEnabled());

        // when
        token1.close();
        AutoCloseable token2 = gate.bindAcceptedPolicy(policyB);
        assertThat(gate.isEnabled()).isEqualTo(policyB.current().traceControlHeadersEnabled());

        token1.close();
        // then
        assertThatThrownBy(() -> gate.bindAcceptedPolicy(policyA))
                .as("stale close of token1 must not clear policy B")
                .isInstanceOf(IllegalStateException.class);

        token2.close();
        token2.close();
        AutoCloseable token3 = gate.bindAcceptedPolicy(policyA);
        token3.close();
    }

    @Test
    void rebindWhileActiveFails() throws Exception {
        // given
        PlatformPropagationGate gate = new PlatformPropagationGate();
        TracingSettingsSource policyA = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.1d);
        TracingSettingsSource policyB = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.9d);

        AutoCloseable token1 = gate.bindAcceptedPolicy(policyA);
        // when/then
        assertThatThrownBy(() -> gate.bindAcceptedPolicy(policyB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has an active");
        token1.close();
    }

    @Test
    void nullPolicyFailsWithoutOpeningRegistration() throws Exception {
        // given
        PlatformPropagationGate gate = new PlatformPropagationGate();
        // when/then
        assertThatThrownBy(() -> gate.bindAcceptedPolicy(null))
                .isInstanceOf(NullPointerException.class);
        AutoCloseable token = gate.bindAcceptedPolicy(
                TracingSettingsSource.isolatedSampling(true, Map.of(), 0.2d));
        token.close();
    }
}
