package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Package-private JMX transport for the Controlled Platform Agent readiness MBean.
 * <p>
 * Owns attribute names, type conversion, and defensive copying only. Endpoint registration is
 * checked separately via {@link #endpointRegistered(ObjectName)} on every attempt, since presence
 * is re-evaluated on each polling iteration by the caller.
 * <p>
 * The per-attribute JMX exception matrix (A-19: distinguishing endpoint disappearance,
 * access-denied, and invocation-failure) is introduced in Slice 2, together with the switch to a
 * single batch {@code getAttributes} read. This class already exposes its final sealed-result
 * shape so that Slice 2 only changes the read strategy, not the probe's public surface.
 */
final class AgentReadinessProbe {

    private final MBeanServer server;

    AgentReadinessProbe(MBeanServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    boolean endpointRegistered(ObjectName readinessName) {
        return server.isRegistered(readinessName);
    }

    ProbeResult read(ObjectName readinessName) {
        try {
            String extensionVersion = stringAttribute(readinessName, "ExtensionVersion");
            int protocolVersion = intAttribute(readinessName, "ProtocolVersion");
            String profile = stringAttribute(readinessName, "Profile");
            String lifecycle = stringAttribute(readinessName, "LifecycleState");
            String failureCode = stringAttribute(readinessName, "FailureCode");
            stringAttribute(readinessName, "FailureMessage");
            Set<String> capabilities = stringSetAttribute(readinessName, "Capabilities");
            boolean sanitizerInstalled = booleanAttribute(readinessName, "SanitizerInstalled");
            boolean samplerInstalled = booleanAttribute(readinessName, "SamplerInstalled");
            boolean requiredSpanProcessorsInstalled =
                    booleanAttribute(readinessName, "RequiredSpanProcessorsInstalled");
            boolean propagationHooksInstalled = booleanAttribute(readinessName, "PropagationHooksInstalled");
            boolean exportPathProtected = booleanAttribute(readinessName, "ExportPathProtected");

            return new ProbeResult.Present(new AgentReadinessSnapshot(
                    extensionVersion,
                    protocolVersion,
                    profile,
                    lifecycle,
                    failureCode,
                    capabilities,
                    sanitizerInstalled,
                    samplerInstalled,
                    requiredSpanProcessorsInstalled,
                    propagationHooksInstalled,
                    exportPathProtected));
        } catch (Exception malformed) {
            return new ProbeResult.Malformed("MALFORMED_READINESS_DESCRIPTOR");
        }
    }

    private String stringAttribute(ObjectName name, String attribute) throws Exception {
        Object value = server.getAttribute(name, attribute);
        if (value instanceof String string) {
            return string;
        }
        throw new IllegalStateException(attribute + " must be String");
    }

    private int intAttribute(ObjectName name, String attribute) throws Exception {
        Object value = server.getAttribute(name, attribute);
        if (value instanceof Integer integer) {
            return integer;
        }
        throw new IllegalStateException(attribute + " must be Integer");
    }

    private boolean booleanAttribute(ObjectName name, String attribute) throws Exception {
        Object value = server.getAttribute(name, attribute);
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IllegalStateException(attribute + " must be Boolean");
    }

    private Set<String> stringSetAttribute(ObjectName name, String attribute) throws Exception {
        Object value = server.getAttribute(name, attribute);
        if (value instanceof String[] array) {
            return new LinkedHashSet<>(Arrays.asList(array));
        }
        throw new IllegalStateException(attribute + " must be String[]");
    }

    /**
     * Sealed transport-level outcome. Mirrors the pattern used by
     * {@code PrivateControlMarkerInspector.ProbeResult}: {@link Present} carries a fully decoded,
     * defensively-copied snapshot; {@link Malformed} carries a stable reason string for any read
     * failure. Slice 2 refines {@link Malformed} into distinct reasons per the A-19 exception
     * matrix; no caller outside this package observes this type.
     */
    sealed interface ProbeResult permits ProbeResult.Present, ProbeResult.Malformed {

        record Present(AgentReadinessSnapshot snapshot) implements ProbeResult {
            public Present {
                Objects.requireNonNull(snapshot, "snapshot");
            }
        }

        record Malformed(String reason) implements ProbeResult {
            public Malformed {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }
}
