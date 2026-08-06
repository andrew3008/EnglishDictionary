package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.Set;

/**
 * Decoded, type-checked values of the Controlled Platform Agent readiness MBean, owned by
 * {@link AgentReadinessProbe}. Carries no policy: protocol/profile/version/capability rules are
 * applied by the observer/classifier that consumes this snapshot.
 * <p>
 * {@code requiredCapabilities} is decoded and shape-validated by the probe starting Slice 2, but is
 * not consulted by any policy until Slice 3 introduces the dedicated classifier.
 */
record AgentReadinessSnapshot(
        String extensionVersion,
        int protocolVersion,
        String profile,
        String lifecycle,
        String failureCode,
        Set<String> capabilities,
        Set<String> requiredCapabilities,
        boolean sanitizerInstalled,
        boolean samplerInstalled,
        boolean requiredSpanProcessorsInstalled,
        boolean propagationHooksInstalled,
        boolean exportPathProtected) {

    AgentReadinessSnapshot {
        capabilities = (capabilities == null) ? Set.of() : Set.copyOf(capabilities);
        requiredCapabilities = (requiredCapabilities == null) ? Set.of() : Set.copyOf(requiredCapabilities);
    }
}
