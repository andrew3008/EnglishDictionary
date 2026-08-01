package space.br1440.platform.tracing.autoconfigure.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Set;

import org.junit.jupiter.api.Test;

class AgentRuntimeContractValidatorTest {

    @Test
    void enabledTracingRequiresReadyControlledExtension() {
        assertThat(AgentRuntimeContractValidator.validate(true, descriptor(AgentRuntimeState.AGENT_READY)))
                .isEqualTo(AgentRuntimeState.AGENT_READY);

        assertThatIllegalStateException()
                .isThrownBy(() -> AgentRuntimeContractValidator.validate(
                        true, descriptor(AgentRuntimeState.EXTENSION_MISSING)))
                .withMessageContaining("state=EXTENSION_MISSING");
    }

    @Test
    void disabledTracingRequiresCompleteRuntimeAbsence() {
        assertThat(AgentRuntimeContractValidator.validate(false, descriptor(AgentRuntimeState.DISABLED)))
                .isEqualTo(AgentRuntimeState.DISABLED);

        assertThatIllegalStateException()
                .isThrownBy(() -> AgentRuntimeContractValidator.validate(
                        false, descriptor(AgentRuntimeState.AGENT_READY)))
                .withMessageContaining("platform.tracing.enabled=false rejected observed runtime state=AGENT_READY");
    }

    @Test
    void descriptorIsRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> AgentRuntimeContractValidator.validate(true, null))
                .withMessage("extension");
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
