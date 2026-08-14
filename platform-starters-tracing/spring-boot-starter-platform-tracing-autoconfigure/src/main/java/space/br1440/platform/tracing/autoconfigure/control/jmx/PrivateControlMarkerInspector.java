package space.br1440.platform.tracing.autoconfigure.control.jmx;

import java.util.HashMap;
import java.util.Map;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeErrorException;
import javax.management.RuntimeMBeanException;

final class PrivateControlMarkerInspector {

    private static final String[] MARKER_ATTRIBUTES = {
            "ProtocolId", "ProtocolVersion", "RuntimeInstanceId",
            "WriterObjectName", "LifecycleState", "MarkerSignature"
    };

    ProbeResult inspect(MBeanServer server, ObjectName marker, ObjectName writer) {
        boolean registered;
        try {
            registered = server.isRegistered(marker);
        } catch (SecurityException exception) {
            return ProbeResult.indeterminate(ProbeReason.ACCESS_DENIED, exception);
        }

        if (!registered) {
            return ProbeResult.absent();
        }

        AttributeList attributes;
        try {
            attributes = server.getAttributes(marker, MARKER_ATTRIBUTES.clone());
        } catch (InstanceNotFoundException exception) {
            return ProbeResult.rejected(ProbeReason.MARKER_DISAPPEARED);
        } catch (ReflectionException | RuntimeMBeanException | RuntimeErrorException exception) {
            return ProbeResult.indeterminate(ProbeReason.MARKER_INVOCATION_FAILED, exception);
        } catch (SecurityException exception) {
            return ProbeResult.indeterminate(ProbeReason.ACCESS_DENIED, exception);
        }

        Map<String, Object> values = new HashMap<>(8);
        ProbeReason shapeRejection = validateShape(attributes, values);
        if (shapeRejection != null) {
            return ProbeResult.rejected(shapeRejection);
        }

        Object protocolIdValue = values.get("ProtocolId");
        Object protocolVersionValue = values.get("ProtocolVersion");
        Object runtimeInstanceIdValue = values.get("RuntimeInstanceId");
        Object writerObjectNameValue = values.get("WriterObjectName");
        Object lifecycleStateValue = values.get("LifecycleState");
        Object markerSignatureValue = values.get("MarkerSignature");

        boolean typesValid = protocolIdValue instanceof String
                && protocolVersionValue instanceof Integer
                && (runtimeInstanceIdValue == null || runtimeInstanceIdValue instanceof String)
                && writerObjectNameValue instanceof String
                && lifecycleStateValue instanceof String
                && markerSignatureValue instanceof String;

        if (!typesValid) {
            return ProbeResult.rejected(ProbeReason.MALFORMED_DESCRIPTOR);
        }

        String runtimeInstanceId = (String) runtimeInstanceIdValue;
        if (runtimeInstanceId == null) {
            return ProbeResult.rejected(ProbeReason.RUNTIME_ID_MISSING);
        }
        if (runtimeInstanceId.isBlank()) {
            return ProbeResult.rejected(ProbeReason.RUNTIME_ID_BLANK);
        }

        MarkerDescriptor descriptor = new MarkerDescriptor(
                (String) protocolIdValue,
                (Integer) protocolVersionValue,
                runtimeInstanceId,
                (String) writerObjectNameValue,
                (String) lifecycleStateValue,
                (String) markerSignatureValue
        );

        ProbeReason predicateRejection = validatePredicates(descriptor);
        if (predicateRejection != null) {
            return ProbeResult.rejected(predicateRejection);
        }

        boolean writerRegistered;
        try {
            writerRegistered = server.isRegistered(writer);
        } catch (SecurityException exception) {
            return ProbeResult.indeterminate(ProbeReason.ACCESS_DENIED, exception);
        }

        if (!writerRegistered) {
            return ProbeResult.rejected(ProbeReason.WRITER_NOT_REGISTERED);
        }

        return ProbeResult.compatible(
                new PrivateControlLocator.LocatedPrivateControl(server, writer, runtimeInstanceId));
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
            if (name == null || isUnknownAttributeName(name) || values.containsKey(name)) {
                return ProbeReason.MALFORMED_DESCRIPTOR;
            }

            values.put(name, attribute.getValue());
        }

        for (String expected : MARKER_ATTRIBUTES) {
            if (!values.containsKey(expected)) {
                return ProbeReason.INCOMPLETE_DESCRIPTOR;
            }
        }

        return null;
    }

    private static ProbeReason validatePredicates(MarkerDescriptor descriptor) {
        if (!PrivateControlLocator.PROTOCOL_ID.equals(descriptor.protocolId())) {
            return ProbeReason.PROTOCOL_ID_MISMATCH;
        }

        if (descriptor.protocolVersion() != PrivateControlLocator.PROTOCOL_VERSION) {
            return ProbeReason.VERSION_MISMATCH;
        }

        if (!PrivateControlLocator.WRITER_NAME.equals(descriptor.writerObjectName())) {
            return ProbeReason.WRITER_NAME_MISMATCH;
        }

        if (!PrivateControlLocator.LIFECYCLE_WRITER_READY.equals(descriptor.lifecycleState())) {
            return ProbeReason.LIFECYCLE_NOT_READY;
        }

        if (!PrivateControlLocator.MARKER_SIGNATURE.equals(descriptor.markerSignature())) {
            return ProbeReason.SIGNATURE_MISMATCH;
        }

        return null;
    }

    private static boolean isUnknownAttributeName(String name) {
        for (String expected : MARKER_ATTRIBUTES) {
            if (expected.equals(name)) {
                return false;
            }
        }

        return true;
    }

    record MarkerDescriptor(String protocolId,
                            int protocolVersion,
                            String runtimeInstanceId,
                            String writerObjectName,
                            String lifecycleState,
                            String markerSignature) {
    }

    enum ProbeReason {
        PROTOCOL_ID_MISMATCH,
        VERSION_MISMATCH,
        RUNTIME_ID_MISSING,
        RUNTIME_ID_BLANK,
        WRITER_NAME_MISMATCH,
        LIFECYCLE_NOT_READY,
        SIGNATURE_MISMATCH,
        WRITER_NOT_REGISTERED,
        INCOMPLETE_DESCRIPTOR,
        MALFORMED_DESCRIPTOR,
        MARKER_DISAPPEARED,
        MARKER_INVOCATION_FAILED,
        ACCESS_DENIED
    }

    sealed interface ProbeResult {

        static ProbeResult absent() {
            return Absent.INSTANCE;
        }

        static ProbeResult rejected(ProbeReason reason) {
            return new Rejected(reason);
        }

        static ProbeResult indeterminate(ProbeReason reason, Throwable cause) {
            return new Indeterminate(reason, cause);
        }

        static ProbeResult compatible(PrivateControlLocator.LocatedPrivateControl control) {
            return new Compatible(control);
        }

        final class Absent implements ProbeResult {
            private static final Absent INSTANCE = new Absent();

            private Absent() {
            }
        }

        record Rejected(ProbeReason reason) implements ProbeResult {
            public Rejected {
                if (reason == null) {
                    throw new IllegalArgumentException("reason must not be null");
                }
            }
        }

        record Indeterminate(ProbeReason reason, Throwable cause) implements ProbeResult {
            public Indeterminate {
                if (reason == null) {
                    throw new IllegalArgumentException("reason must not be null");
                }

                if (cause == null) {
                    throw new IllegalArgumentException("cause must not be null");
                }
            }
        }

        record Compatible(PrivateControlLocator.LocatedPrivateControl control) implements ProbeResult {
            public Compatible {
                if (control == null) {
                    throw new IllegalArgumentException("control must not be null");
                }
            }
        }
    }
}
