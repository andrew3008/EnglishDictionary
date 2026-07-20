package space.br1440.platform.tracing.autoconfigure.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.Set;

import org.junit.jupiter.api.Test;

class SdkModeResolverTest {

    @Test
    void productionSurfaceContainsOnlyAgentAndDisabled() {
        assertThat(SdkMode.values()).containsExactly(SdkMode.AGENT, SdkMode.DISABLED);
    }

    @Test
    void agentRequiresReadyControlledExtension() {
        assertThat(SdkModeResolver.resolve(SdkMode.AGENT, true, descriptor(AgentRuntimeState.AGENT_READY)))
                .isEqualTo(SdkMode.AGENT);

        assertThatIllegalStateException()
                .isThrownBy(() -> SdkModeResolver.resolve(
                        SdkMode.AGENT, true, descriptor(AgentRuntimeState.EXTENSION_MISSING)))
                .withMessageContaining("state=EXTENSION_MISSING");
    }

    @Test
    void disabledRequiresCompleteRuntimeAbsence() {
        assertThat(SdkModeResolver.resolve(SdkMode.DISABLED, false, descriptor(AgentRuntimeState.DISABLED)))
                .isEqualTo(SdkMode.DISABLED);

        assertThatIllegalStateException()
                .isThrownBy(() -> SdkModeResolver.resolve(
                        SdkMode.DISABLED, false, descriptor(AgentRuntimeState.AGENT_READY)))
                .withMessageContaining("mode=DISABLED rejected observed runtime state=AGENT_READY");
    }

    @Test
    void enabledAndModeMustDescribeTheSameState() {
        assertThatIllegalStateException()
                .isThrownBy(() -> SdkModeResolver.resolve(
                        SdkMode.DISABLED, true, descriptor(AgentRuntimeState.DISABLED)))
                .withMessageContaining("enabled=true requires platform.tracing.sdk.mode=AGENT");
        assertThatIllegalStateException()
                .isThrownBy(() -> SdkModeResolver.resolve(
                        SdkMode.AGENT, false, descriptor(AgentRuntimeState.AGENT_READY)))
                .withMessageContaining("enabled=false requires platform.tracing.sdk.mode=DISABLED");
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
