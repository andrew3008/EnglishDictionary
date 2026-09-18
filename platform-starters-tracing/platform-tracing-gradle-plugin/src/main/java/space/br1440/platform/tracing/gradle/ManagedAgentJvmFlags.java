package space.br1440.platform.tracing.gradle;

import java.util.List;

final class ManagedAgentJvmFlags {

    private static final String EXPECTED_SHA256_PROPERTY = "platform.tracing.controlled-agent.expected-sha256";
    private static final String JMX_MODE_PROPERTY = "platform.tracing.jmx.remote.mode";
    private static final String JMX_LISTENER_CONFIRMED_PROPERTY = "platform.tracing.jmx.remote.listener-confirmed";
    private static final String JMX_AUTHORIZATION_FORWARDER_PROPERTY = "platform.tracing.jmx.remote.authorization-forwarder-installed";
    private static final String DISABLE_ATTACH_MECHANISM_FLAG = "-XX:+DisableAttachMechanism";

    private ManagedAgentJvmFlags() {
    }

    static List<String> forAgentPath(String javaAgentPath, String expectedSha256) {
        return List.of(
                "-javaagent:" + javaAgentPath,
                "-D" + EXPECTED_SHA256_PROPERTY + "=" + expectedSha256,
                "-D" + JMX_MODE_PROPERTY + "=REMOTE_PROHIBITED",
                "-D" + JMX_LISTENER_CONFIRMED_PROPERTY + "=false",
                "-D" + JMX_AUTHORIZATION_FORWARDER_PROPERTY + "=false",
                DISABLE_ATTACH_MECHANISM_FLAG
        );
    }
}
