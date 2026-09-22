package space.br1440.platform.tracing.gradle;

import java.util.List;

import lombok.experimental.UtilityClass;

@UtilityClass
final class ManagedAgentJvmFlags {

    static final String EXPECTED_SHA256_PROPERTY = "platform.tracing.controlled-agent.expected-sha256";

    static List<String> forAgentPath(String javaAgentPath, String expectedSha256) {
        return List.of(
                "-javaagent:" + javaAgentPath,
                "-D" + EXPECTED_SHA256_PROPERTY + "=" + expectedSha256
        );
    }
}
