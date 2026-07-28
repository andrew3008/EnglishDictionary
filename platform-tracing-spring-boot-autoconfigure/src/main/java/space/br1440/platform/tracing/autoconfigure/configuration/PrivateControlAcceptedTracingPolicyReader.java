package space.br1440.platform.tracing.autoconfigure.configuration;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import space.br1440.platform.tracing.autoconfigure.diagnostics.OtelAgentLogLevel;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxObjectNames;

/**
 * Читает accepted state только через утверждённые read-only JMX-проекции платформы.
 */
final class PrivateControlAcceptedTracingPolicyReader implements AcceptedTracingPolicyReader {

    private final MBeanServer platformServer;

    PrivateControlAcceptedTracingPolicyReader() {
        this(ManagementFactory.getPlatformMBeanServer());
    }

    PrivateControlAcceptedTracingPolicyReader(MBeanServer platformServer) {
        this.platformServer = Objects.requireNonNull(platformServer, "platformServer");
    }

    @Override
    public AcceptedTracingPolicyView current() {
        ObjectName control = PlatformTracingJmxObjectNames.CONTROL;
        requireRegistered(control, "accepted policy control projection");
        requireRegistered(PlatformTracingJmxObjectNames.SAMPLING, "sampling projection");
        requireRegistered(PlatformTracingJmxObjectNames.VALIDATION, "validation projection");
        requireRegistered(PlatformTracingJmxObjectNames.EXPORT, "export projection");
        requireRegistered(PlatformTracingJmxObjectNames.DIAGNOSTICS, "diagnostics projection");

        for (int attempt = 0; attempt < 2; attempt++) {
            AcceptedMetadata before = acceptedMetadata(control);
            CompleteTracingPolicySnapshot policy = readCompletePolicy();
            AcceptedMetadata after = acceptedMetadata(control);
            if (before.sameAcceptedBase(after)) {
                return after.toView(policy);
            }
        }
        throw new IllegalStateException("Accepted policy changed during read projection");
    }

    private CompleteTracingPolicySnapshot readCompletePolicy() {
        return new CompleteTracingPolicySnapshot(
                booleanAttribute(PlatformTracingJmxObjectNames.SAMPLING, "SamplerEnabled"),
                doubleAttribute(PlatformTracingJmxObjectNames.SAMPLING, "SamplingRatio"),
                routeRatios(),
                booleanAttribute(PlatformTracingJmxObjectNames.VALIDATION, "ValidationEnabled"),
                booleanAttribute(PlatformTracingJmxObjectNames.VALIDATION, "ValidationStrict"),
                booleanAttribute(PlatformTracingJmxObjectNames.EXPORT, "ExportEnabled"),
                booleanAttribute(PlatformTracingJmxObjectNames.DIAGNOSTICS, "PropagationEnabled"),
                OtelAgentLogLevel.valueOf(
                        stringAttribute(
                                PlatformTracingJmxObjectNames.DIAGNOSTICS,
                                "PlatformLogLevel")));
    }

    private Map<String, Double> routeRatios() {
        Object raw = attribute(PlatformTracingJmxObjectNames.SAMPLING, "RouteRatios");
        if (!(raw instanceof TabularData table)) {
            throw new IllegalStateException("RouteRatios is not an approved TabularData projection");
        }
        Map<String, Double> result = new TreeMap<>();
        for (Object rawRow : table.values()) {
            if (!(rawRow instanceof CompositeData row)) {
                throw new IllegalStateException("RouteRatios contains a non-CompositeData row");
            }
            Object route = row.get("routePrefix");
            Object ratio = row.get("ratio");
            if (!(route instanceof String routePrefix) || !(ratio instanceof Double routeRatio)) {
                throw new IllegalStateException("RouteRatios row violates the approved schema");
            }
            if (result.put(routePrefix, routeRatio) != null) {
                throw new IllegalStateException("RouteRatios contains a duplicate routePrefix");
            }
        }
        return result;
    }

    private AcceptedMetadata acceptedMetadata(ObjectName control) {
        return new AcceptedMetadata(
                stringAttribute(control, "RuntimeInstanceId"),
                longAttribute(control, "AcceptedSequence"),
                stringAttribute(control, "AcceptedPolicyDigest"),
                stringAttribute(control, "LastApplyOutcome"),
                stringAttribute(control, "LastAttemptId"),
                stringAttribute(control, "LastFailureCode"),
                booleanAttribute(control, "StructuralDrift"),
                booleanAttribute(control, "RestartRequired"));
    }

    private void requireRegistered(ObjectName objectName, String role) {
        if (!platformServer.isRegistered(objectName)) {
            throw new AcceptedTracingPolicyUnavailableException(role + " is not registered");
        }
    }

    private Object attribute(ObjectName objectName, String attribute) {
        try {
            return platformServer.getAttribute(objectName, attribute);
        } catch (InstanceNotFoundException exception) {
            throw new AcceptedTracingPolicyUnavailableException(
                    "Approved platform projection disappeared during read",
                    exception);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot read approved platform projection " + attribute,
                    exception);
        }
    }

    private String stringAttribute(ObjectName objectName, String attribute) {
        Object value = attribute(objectName, attribute);
        if (value instanceof String text) {
            return text;
        }
        throw wrongType(attribute, "String");
    }

    private long longAttribute(ObjectName objectName, String attribute) {
        Object value = attribute(objectName, attribute);
        if (value instanceof Long number) {
            return number;
        }
        throw wrongType(attribute, "long");
    }

    private double doubleAttribute(ObjectName objectName, String attribute) {
        Object value = attribute(objectName, attribute);
        if (value instanceof Double number) {
            return number;
        }
        throw wrongType(attribute, "double");
    }

    private boolean booleanAttribute(ObjectName objectName, String attribute) {
        Object value = attribute(objectName, attribute);
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw wrongType(attribute, "boolean");
    }

    private static IllegalStateException wrongType(String attribute, String expectedType) {
        return new IllegalStateException(
                attribute + " is not the approved " + expectedType + " projection");
    }

    private record AcceptedMetadata(
            String runtimeInstanceId,
            long acceptedSequence,
            String payloadDigest,
            String lastApplyOutcome,
            String lastAttemptId,
            String lastFailureCode,
            boolean structuralDrift,
            boolean restartRequired) {

        private boolean sameAcceptedBase(AcceptedMetadata other) {
            return acceptedSequence == other.acceptedSequence
                    && runtimeInstanceId.equals(other.runtimeInstanceId)
                    && payloadDigest.equals(other.payloadDigest);
        }

        private AcceptedTracingPolicyView toView(CompleteTracingPolicySnapshot policy) {
            return new AcceptedTracingPolicyView(
                    runtimeInstanceId,
                    acceptedSequence,
                    payloadDigest,
                    policy,
                    lastApplyOutcome,
                    lastAttemptId,
                    lastFailureCode,
                    structuralDrift,
                    restartRequired);
        }
    }
}
