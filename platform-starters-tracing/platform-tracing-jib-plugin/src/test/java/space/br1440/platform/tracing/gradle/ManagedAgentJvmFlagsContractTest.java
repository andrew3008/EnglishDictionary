package space.br1440.platform.tracing.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ManagedAgentJvmFlagsContractTest {

    @Test
    void forAgentPath_returns_the_full_set_of_managed_flags_in_the_fixed_order() {
        // given
        String agentPath = "/agent.jar";
        String expectedSha256 = "deadbeef";

        // when
        var arguments = ManagedAgentJvmFlags.forAgentPath(agentPath, expectedSha256);

        // then
        assertThat(arguments).containsExactly(
                "-javaagent:/agent.jar",
                "-Dplatform.tracing.controlled-agent.expected-sha256=deadbeef"
        );
    }

    @Test
    void forAgentPath_never_emits_management_agent_activation_arguments() {
        // given
        // when
        var arguments = ManagedAgentJvmFlags.forAgentPath("/agent.jar", "deadbeef");

        // then
        assertThat(arguments)
                .noneMatch(argument -> argument.startsWith("-Dcom.sun.management"))
                .doesNotContain("-XX:+ManagementServer");
    }
}
