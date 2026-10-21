package space.br1440.platform.tracing.otel.javaagent.propagation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyMutationAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;

class PlatformPropagationGateRegistrationTest {

    @Test
    void bindCloseRestoresPreviousAndStaleCloseDoesNotClearNewerBinding() throws Exception {
        // given
        PlatformPropagationGate gate = new PlatformPropagationGate();
        TracingSettingsSource policyA = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of(), 0.1d);
        TracingSettingsSource policyB = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of(), 0.9d);

        AutoCloseable token1 = gate.bindAcceptedPolicy(policyA);
        assertThat(gate.isEnabled()).isEqualTo(policyA.current().traceControlHeadersEnabled());

        // when
        token1.close();
        AutoCloseable token2 = gate.bindAcceptedPolicy(policyB);
        assertThat(gate.isEnabled()).isEqualTo(policyB.current().traceControlHeadersEnabled());

        token1.close();
        // then
        assertThatThrownBy(() -> {
            try (AutoCloseable ignored = gate.bindAcceptedPolicy(policyA)) {
                fail("registration opened");
            }
        })
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
        TracingSettingsSource policyA = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of(), 0.1d);
        TracingSettingsSource policyB = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of(), 0.9d);

        AutoCloseable token1 = gate.bindAcceptedPolicy(policyA);
        // when/then
        assertThatThrownBy(() -> {
            try (AutoCloseable ignored = gate.bindAcceptedPolicy(policyB)) {
                fail("registration opened");
            }
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has an active");
        token1.close();
    }

    @Test
    void nullPolicyFailsWithoutOpeningRegistration() throws Exception {
        // given
        PlatformPropagationGate gate = new PlatformPropagationGate();
        // when/then
        assertThatThrownBy(() -> {
            try (AutoCloseable ignored = gate.bindAcceptedPolicy(null)) {
                fail("registration opened");
            }
        })
                .isInstanceOf(NullPointerException.class);
        AutoCloseable token = gate.bindAcceptedPolicy(
                TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of(), 0.2d));
        token.close();
    }

    @Test
    @ResourceLock(AgentLogCapture.RESOURCE_LOCK)
    void unboundStateUsesCapturedDefaultAndBoundStateReadsAcceptedPolicy() throws Exception {
        // given
        PlatformPropagationGate gate = new PlatformPropagationGate();
        TracingSettingsSource policy = TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random());
        assertThat(AcceptedPolicyMutationAccess.applyTraceControlHeaders(policy, false))
                .isEqualTo(SettingsUpdateStatus.APPLIED);

        // when
        boolean beforeBind = gate.isEnabled();
        AutoCloseable registration = gate.bindAcceptedPolicy(policy);
        boolean whileBound = gate.isEnabled();
        registration.close();
        boolean afterClose = gate.isEnabled();

        // then
        assertThat(beforeBind).isEqualTo(TracingSettingsSource.defaultPolicy().traceControlHeadersEnabled());
        assertThat(whileBound).isFalse();
        assertThat(afterClose).isEqualTo(beforeBind);
        gate.bindAcceptedPolicy(policy).close();
    }
}
