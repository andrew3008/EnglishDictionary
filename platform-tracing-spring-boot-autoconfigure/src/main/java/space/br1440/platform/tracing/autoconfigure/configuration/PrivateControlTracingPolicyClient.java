package space.br1440.platform.tracing.autoconfigure.configuration;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import space.br1440.platform.tracing.autoconfigure.diagnostics.OtelAgentLogLevel;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxObjectNames;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlLocator;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlOpenTypeCodec;

/**
 * Единый application-side клиент утверждённых read-only platform projections и private writer.
 */
final class PrivateControlTracingPolicyClient
        implements TracingPolicySnapshotClient, AcceptedTracingPolicyReader {

    private final MBeanServer platformServer;
    private final PrivateControlLocator privateLocator;
    private final PrivateControlOpenTypeCodec codec;

    PrivateControlTracingPolicyClient() {
        this(
                ManagementFactory.getPlatformMBeanServer(),
                new PrivateControlLocator(),
                new PrivateControlOpenTypeCodec());
    }

    PrivateControlTracingPolicyClient(
            MBeanServer platformServer,
            PrivateControlLocator privateLocator,
            PrivateControlOpenTypeCodec codec) {
        this.platformServer = Objects.requireNonNull(platformServer, "platformServer");
        this.privateLocator = Objects.requireNonNull(privateLocator, "privateLocator");
        this.codec = Objects.requireNonNull(codec, "codec");
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

    @Override
    public TracingPolicyDeliveryResult applySnapshot(
            ExpectedAcceptedState expectedState,
            CompleteTracingPolicySnapshot snapshot,
            TracingPolicySource source,
            TracingPolicyApplyTrigger trigger,
            String sourceRevision) {
        Objects.requireNonNull(expectedState, "expectedState");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(trigger, "trigger");
        validateSourceTrigger(source, trigger);

        String attemptId = UUID.randomUUID().toString();
        try {
            PrivateControlLocator.LocatedPrivateControl located = privateLocator.locate();
            PrivateControlOpenTypeCodec.PrivateControlSnapshot request =
                    new PrivateControlOpenTypeCodec.PrivateControlSnapshot(
                            expectedState.runtimeInstanceId(),
                            expectedState.acceptedSequence(),
                            attemptId,
                            wireAuthority(source),
                            trigger.wireToken(),
                            sourceRevision == null ? "" : sourceRevision,
                            CompleteTracingPolicyDigest.sha256(snapshot),
                            snapshot.samplingEnabled(),
                            snapshot.samplingRatio(),
                            snapshot.samplingRouteRatios(),
                            snapshot.validationEnabled(),
                            snapshot.validationStrict(),
                            snapshot.exporterEnabled(),
                            snapshot.traceControlHeadersEnabled(),
                            snapshot.diagnosticsLogLevel().name());
            CompositeData rawResponse = invokeApply(located, codec.encodeRequest(request));
            PrivateControlOpenTypeCodec.PrivateControlResponse response =
                    codec.decodeResponse(rawResponse);
            return new TracingPolicyDeliveryResult(
                    outcome(response.outcome()),
                    response.attemptId().isBlank() ? attemptId : response.attemptId(),
                    response.messageCode().isBlank() ? "NONE" : response.messageCode(),
                    response.acceptedSequence());
        } catch (RuntimeException exception) {
            return new TracingPolicyDeliveryResult(
                    TracingPolicyDeliveryOutcome.FAILED,
                    attemptId,
                    "INTERNAL_FAILURE",
                    expectedState.acceptedSequence());
        }
    }

    void verifyReady() {
        privateLocator.locate();
        current();
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
            throw new IllegalStateException(role + " is not registered");
        }
    }

    private Object attribute(ObjectName objectName, String attribute) {
        try {
            return platformServer.getAttribute(objectName, attribute);
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

    private static CompositeData invokeApply(
            PrivateControlLocator.LocatedPrivateControl located,
            CompositeData request) {
        try {
            Object raw = located.server().invoke(
                    located.writerObjectName(),
                    "applySnapshot",
                    new Object[]{request},
                    new String[]{CompositeData.class.getName()});
            if (raw instanceof CompositeData response) {
                return response;
            }
            throw new IllegalStateException("private applySnapshot returned non-CompositeData");
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("private applySnapshot invocation failed", exception);
        }
    }

    private static void validateSourceTrigger(
            TracingPolicySource source,
            TracingPolicyApplyTrigger trigger) {
        if (source == TracingPolicySource.CONFIG_SERVER
                && trigger == TracingPolicyApplyTrigger.ACTUATOR) {
            throw new IllegalArgumentException("Config Server cannot use the Actuator trigger");
        }
        if (source == TracingPolicySource.ACTUATOR
                && trigger != TracingPolicyApplyTrigger.ACTUATOR) {
            throw new IllegalArgumentException("Actuator must use the Actuator trigger");
        }
    }

    private static String wireAuthority(TracingPolicySource source) {
        return switch (source) {
            case CONFIG_SERVER -> "CONFIG_SERVER";
            // Wire-токен P0 заморожен; application-facing authority остаётся ACTUATOR.
            case ACTUATOR -> "DEV_ACTUATOR";
        };
    }

    private static TracingPolicyDeliveryOutcome outcome(String token) {
        try {
            return TracingPolicyDeliveryOutcome.valueOf(token);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unknown private control outcome: " + token, exception);
        }
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
