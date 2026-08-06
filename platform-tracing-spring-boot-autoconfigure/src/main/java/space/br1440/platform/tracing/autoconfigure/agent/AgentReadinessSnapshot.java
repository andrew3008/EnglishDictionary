package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.Set;

/**
 * Decoded, type-checked values of the Controlled Platform Agent readiness MBean, owned by
 * {@link AgentReadinessProbe}. Carries no policy: protocol/profile/version/capability rules are
 * applied by the observer/classifier that consumes this snapshot.
 */
record AgentReadinessSnapshot(
        String extensionVersion,
        int protocolVersion,
        String profile,
        String lifecycle,
        String failureCode,
        Set<String> capabilities,
        boolean sanitizerInstalled,
        boolean samplerInstalled,
        boolean requiredSpanProcessorsInstalled,
        boolean propagationHooksInstalled,
        boolean exportPathProtected) {

    AgentReadinessSnapshot {
        capabilities = (capabilities == null) ? Set.of() : Set.copyOf(capabilities);
    }
}
