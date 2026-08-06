package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeErrorException;
import javax.management.RuntimeMBeanException;

/**
 * Package-private JMX transport for the Controlled Platform Agent readiness MBean.
 * <p>
 * Owns attribute names, batch decoding, and defensive copying only. Protocol, profile, version, and
 * capability policy stay in the observer/classifier that consumes {@link AgentReadinessSnapshot}.
 * <p>
 * Endpoint registration is checked separately via {@link #endpointRegistered(ObjectName)} on every
 * polling attempt by the caller; {@link #read(ObjectName)} performs exactly one
 * {@link MBeanServer#getAttributes(ObjectName, String[])} call per invocation and never falls back
 * to reading individual attributes one at a time. A single batch call is not atomic with respect to
 * concurrent writers on the agent side — it only guarantees that this class issues one round trip.
 * <p>
 * {@code FailureMessage} is intentionally never requested: it is a recording-only diagnostic proof
 * channel on the agent side, never consumed by application policy.
 */
final class AgentReadinessProbe {

    /**
     * Exactly the 12 attributes the application needs. The physical readiness MBean exposes a
     * 13th attribute, {@code FailureMessage}, which is never requested here.
     */
    static final Set<String> REQUESTED_ATTRIBUTE_NAMES = Set.of(
            "ExtensionVersion", "ProtocolVersion", "Profile", "LifecycleState", "FailureCode",
            "Capabilities", "RequiredCapabilities",
            "SanitizerInstalled", "SamplerInstalled", "RequiredSpanProcessorsInstalled",
            "PropagationHooksInstalled", "ExportPathProtected");

    private static final String[] REQUESTED_ATTRIBUTES_ARRAY =
            REQUESTED_ATTRIBUTE_NAMES.toArray(String[]::new);

    private final MBeanServer server;

    AgentReadinessProbe(MBeanServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    boolean endpointRegistered(ObjectName readinessName) {
        return server.isRegistered(readinessName);
    }

    ProbeResult read(ObjectName readinessName) {
        AttributeList attributes;
        try {
            attributes = server.getAttributes(readinessName, REQUESTED_ATTRIBUTES_ARRAY.clone());
        } catch (InstanceNotFoundException disappeared) {
            return new ProbeResult.Rejected(ProbeReason.ENDPOINT_DISAPPEARED);
        } catch (ReflectionException | RuntimeMBeanException | RuntimeErrorException invocationFailed) {
            return new ProbeResult.Indeterminate(ProbeReason.INVOCATION_FAILED, invocationFailed);
        } catch (SecurityException accessDenied) {
            return new ProbeResult.Indeterminate(ProbeReason.ACCESS_DENIED, accessDenied);
        }

        Map<String, Object> values = new HashMap<>(16);
        ProbeReason shapeRejection = validateShape(attributes, values);
        if (shapeRejection != null) {
            return new ProbeResult.Rejected(shapeRejection);
        }

        return decode(values);
    }

    /**
     * JMX permits a batch read to silently return fewer attributes than requested (a partial
     * {@link AttributeList}) instead of throwing. Any deviation from exactly the requested attribute
     * names — unexpected name, duplicate name, non-{@link Attribute} element, or a missing expected
     * name — is an immediate terminal rejection. This method never re-reads a missing attribute
     * individually.
     */
    private static ProbeReason validateShape(AttributeList attributes, Map<String, Object> values) {
        if (attributes == null) {
            return ProbeReason.MALFORMED_DESCRIPTOR;
        }

        for (Object element : attributes) {
            if (!(element instanceof Attribute attribute)) {
                return ProbeReason.MALFORMED_DESCRIPTOR;
            }

            String name = attribute.getName();
            if (name == null || !REQUESTED_ATTRIBUTE_NAMES.contains(name) || values.containsKey(name)) {
                return ProbeReason.MALFORMED_DESCRIPTOR;
            }

            values.put(name, attribute.getValue());
        }

        if (values.size() != REQUESTED_ATTRIBUTE_NAMES.size()) {
            return ProbeReason.INCOMPLETE_DESCRIPTOR;
        }

        return null;
    }

    private static ProbeResult decode(Map<String, Object> values) {
        Object extensionVersion = values.get("ExtensionVersion");
        Object protocolVersion = values.get("ProtocolVersion");
        Object profile = values.get("Profile");
        Object lifecycle = values.get("LifecycleState");
        Object failureCode = values.get("FailureCode");
        Object capabilities = values.get("Capabilities");
        Object requiredCapabilities = values.get("RequiredCapabilities");
        Object sanitizerInstalled = values.get("SanitizerInstalled");
        Object samplerInstalled = values.get("SamplerInstalled");
        Object requiredSpanProcessorsInstalled = values.get("RequiredSpanProcessorsInstalled");
        Object propagationHooksInstalled = values.get("PropagationHooksInstalled");
        Object exportPathProtected = values.get("ExportPathProtected");

        boolean typesValid = extensionVersion instanceof String
                && protocolVersion instanceof Integer
                && profile instanceof String
                && lifecycle instanceof String
                && failureCode instanceof String
                && capabilities instanceof String[]
                && requiredCapabilities instanceof String[]
                && sanitizerInstalled instanceof Boolean
                && samplerInstalled instanceof Boolean
                && requiredSpanProcessorsInstalled instanceof Boolean
                && propagationHooksInstalled instanceof Boolean
                && exportPathProtected instanceof Boolean;

        if (!typesValid) {
            return new ProbeResult.Rejected(ProbeReason.MALFORMED_DESCRIPTOR);
        }

        return new ProbeResult.Present(new AgentReadinessSnapshot(
                (String) extensionVersion,
                (Integer) protocolVersion,
                (String) profile,
                (String) lifecycle,
                (String) failureCode,
                toSet((String[]) capabilities),
                toSet((String[]) requiredCapabilities),
                (Boolean) sanitizerInstalled,
                (Boolean) samplerInstalled,
                (Boolean) requiredSpanProcessorsInstalled,
                (Boolean) propagationHooksInstalled,
                (Boolean) exportPathProtected));
    }

    private static Set<String> toSet(String[] array) {
        return new LinkedHashSet<>(Arrays.asList(array));
    }

    /**
     * A-19 JMX exception matrix: distinguishes why a batch read failed. Only {@code
     * ENDPOINT_DISAPPEARED}, {@code ACCESS_DENIED}, and {@code INVOCATION_FAILED} correspond to a
     * caught JMX exception category; the remaining two are shape-validation failures on an
     * otherwise successful read.
     */
    enum ProbeReason {
        /** {@link InstanceNotFoundException}: MBean unregistered between presence check and read. */
        ENDPOINT_DISAPPEARED,
        /** {@link SecurityException} raised by the MBeanServer or the MBean implementation. */
        ACCESS_DENIED,
        /** {@link ReflectionException}, {@link RuntimeMBeanException}, or {@link RuntimeErrorException}. */
        INVOCATION_FAILED,
        /** Batch read returned fewer attributes than requested (partial {@link AttributeList}). */
        INCOMPLETE_DESCRIPTOR,
        /** Unexpected/duplicate attribute name, non-{@link Attribute} element, or wrong value type. */
        MALFORMED_DESCRIPTOR
    }

    /**
     * Sealed transport-level outcome, mirroring the pattern used by
     * {@code PrivateControlMarkerInspector.ProbeResult}: {@link Present} carries a fully decoded,
     * defensively-copied snapshot; {@link Rejected} is a terminal structural rejection with no
     * further diagnostic value; {@link Indeterminate} is a terminal JMX-exception outcome that
     * retains its cause for diagnostics. No caller outside this package observes this type.
     */
    sealed interface ProbeResult permits ProbeResult.Present, ProbeResult.Rejected, ProbeResult.Indeterminate {

        record Present(AgentReadinessSnapshot snapshot) implements ProbeResult {
            public Present {
                Objects.requireNonNull(snapshot, "snapshot");
            }
        }

        record Rejected(ProbeReason reason) implements ProbeResult {
            public Rejected {
                Objects.requireNonNull(reason, "reason");
            }
        }

        record Indeterminate(ProbeReason reason, Throwable cause) implements ProbeResult {
            public Indeterminate {
                Objects.requireNonNull(reason, "reason");
                Objects.requireNonNull(cause, "cause");
            }
        }
    }
}
