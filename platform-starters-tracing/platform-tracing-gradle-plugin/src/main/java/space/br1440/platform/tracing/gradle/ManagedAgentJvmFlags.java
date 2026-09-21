package space.br1440.platform.tracing.gradle;

import java.util.List;

final class ManagedAgentJvmFlags {

    private static final String EXPECTED_SHA256_PROPERTY = "platform.tracing.controlled-agent.expected-sha256";

    private ManagedAgentJvmFlags() {
    }

    static List<String> forAgentPath(String javaAgentPath, String expectedSha256) {
        return List.of(
                "-javaagent:" + javaAgentPath,
                "-D" + EXPECTED_SHA256_PROPERTY + "=" + expectedSha256
        );
    }
}
