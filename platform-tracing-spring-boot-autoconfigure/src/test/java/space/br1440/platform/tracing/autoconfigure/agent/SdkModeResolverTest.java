package space.br1440.platform.tracing.autoconfigure.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Set;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingMode;

class TracingRuntimeStateResolverTest {

    @Test
    void productionSurfaceContainsOnlyAgentAndDisabled() {
        assertThat(TracingMode.values()).containsExactly(TracingMode.AGENT, TracingMode.DISABLED);
    }

    @Test
    void agentRequiresReadyControlledExtension() {
        assertThat(TracingRuntimeStateResolver.resolve(TracingMode.AGENT, descriptor(AgentRuntimeState.AGENT_READY)))
                .isEqualTo(TracingMode.AGENT);

        assertThatIllegalStateException()
                .isThrownBy(() -> TracingRuntimeStateResolver.resolve(
                        TracingMode.AGENT, descriptor(AgentRuntimeState.EXTENSION_MISSING)))
                .withMessageContaining("state=EXTENSION_MISSING");
    }

    @Test
    void disabledRequiresCompleteRuntimeAbsence() {
        assertThat(TracingRuntimeStateResolver.resolve(TracingMode.DISABLED, descriptor(AgentRuntimeState.DISABLED)))
                .isEqualTo(TracingMode.DISABLED);

        assertThatIllegalStateException()
                .isThrownBy(() -> TracingRuntimeStateResolver.resolve(
                        TracingMode.DISABLED, descriptor(AgentRuntimeState.AGENT_READY)))
                .withMessageContaining("mode=DISABLED rejected observed runtime state=AGENT_READY");
    }

    @Test
    void configuredModeIsRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> TracingRuntimeStateResolver.resolve(null, descriptor(AgentRuntimeState.DISABLED)))
                .withMessage("platform.tracing.mode must be configured");
    }

    private static AgentExtensionDescriptor descriptor(AgentRuntimeState state) {
        return new AgentExtensionDescriptor(
                state,
                false,
                false,
                "development",
                1,
                "platform-agent-secure-v1",
                state == AgentRuntimeState.AGENT_READY ? "READY" : "",
                "",
                Set.of());
    }
}
