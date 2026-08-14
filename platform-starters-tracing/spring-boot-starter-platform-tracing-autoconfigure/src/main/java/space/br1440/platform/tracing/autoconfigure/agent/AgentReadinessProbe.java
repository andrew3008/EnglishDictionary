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

final class AgentReadinessProbe {

    static final Set<String> REQUESTED_ATTRIBUTE_NAMES = Set.of(
            "ExtensionVersion", "ProtocolVersion", "Profile", "LifecycleState", "FailureCode",
            "Capabilities"
    );

    private static final String[] REQUESTED_ATTRIBUTES_ARRAY = REQUESTED_ATTRIBUTE_NAMES.toArray(String[]::new);

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

        boolean typesValid = extensionVersion instanceof String
                && protocolVersion instanceof Integer
                && profile instanceof String
                && lifecycle instanceof String
                && failureCode instanceof String
                && capabilities instanceof String[];

        if (!typesValid) {
            return new ProbeResult.Rejected(ProbeReason.MALFORMED_DESCRIPTOR);
        }

        return new ProbeResult.Present(
                new AgentReadinessSnapshot(
                        new AgentReadinessSnapshot.Protocol(
                                (String) extensionVersion,
                                (Integer) protocolVersion,
                                (String) profile
                        ),

                        new AgentReadinessSnapshot.Status(
                                (String) lifecycle,
                                (String) failureCode
                        ),
                        toSet((String[]) capabilities)
                )
        );
    }

    private static Set<String> toSet(String[] array) {
        return new LinkedHashSet<>(Arrays.asList(array));
    }

    enum ProbeReason {
        ENDPOINT_DISAPPEARED,
        ACCESS_DENIED,
        INVOCATION_FAILED,
        INCOMPLETE_DESCRIPTOR,
        MALFORMED_DESCRIPTOR
    }

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
