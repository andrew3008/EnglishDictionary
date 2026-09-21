package space.br1440.platform.tracing.gradle;

import java.lang.management.ManagementFactory;

public final class ManagedAgentJvmFlagsLauncherProbe {

    private static final String PROBE_PROPERTY_PREFIX = "MANAGED_AGENT_PROBE_PROPERTY ";
    private static final String PROBE_INPUT_ARGUMENT_PREFIX = "MANAGED_AGENT_PROBE_INPUT_ARGUMENT ";
    private static final String[] MANAGED_SYSTEM_PROPERTIES = {
            "platform.tracing.controlled-agent.expected-sha256"
    };

    private ManagedAgentJvmFlagsLauncherProbe() {
    }

    public static void main(String[] arguments) {
        for (String property : MANAGED_SYSTEM_PROPERTIES) {
            System.out.println(PROBE_PROPERTY_PREFIX + property + "=" + System.getProperty(property));
        }

        for (String inputArgument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            System.out.println(PROBE_INPUT_ARGUMENT_PREFIX + inputArgument);
        }
    }
}
