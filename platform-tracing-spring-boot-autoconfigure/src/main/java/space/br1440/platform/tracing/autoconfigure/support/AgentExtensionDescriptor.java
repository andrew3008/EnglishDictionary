package space.br1440.platform.tracing.autoconfigure.support;

import java.util.Set;

/**
 * Иммутабельный application-side снимок classloader-neutral readiness MBean.
 */
public record AgentExtensionDescriptor(
        AgentRuntimeState state,
        boolean agentMarkerVisible,
        boolean readinessEndpointPresent,
        String extensionVersion,
        int protocolVersion,
        String profile,
        String lifecycle,
        String failureCode,
        Set<String> capabilities) {

    public AgentExtensionDescriptor {
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        extensionVersion = nullSafe(extensionVersion);
        profile = nullSafe(profile);
        lifecycle = nullSafe(lifecycle);
        failureCode = nullSafe(failureCode);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
