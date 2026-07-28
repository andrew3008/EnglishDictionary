package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.Objects;
import java.util.UUID;

import javax.management.InstanceNotFoundException;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlLocator;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlOpenTypeCodec;

/**
 * Application-side клиент единственного private aggregate writer.
 */
final class PrivateControlTracingPolicyClient implements TracingPolicySnapshotClient {

    private final PrivateControlLocator privateLocator;
    private final PrivateControlOpenTypeCodec codec;

    PrivateControlTracingPolicyClient() {
        this(new PrivateControlLocator(), new PrivateControlOpenTypeCodec());
    }

    PrivateControlTracingPolicyClient(
            PrivateControlLocator privateLocator,
            PrivateControlOpenTypeCodec codec) {
        this.privateLocator = Objects.requireNonNull(privateLocator, "privateLocator");
        this.codec = Objects.requireNonNull(codec, "codec");
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
        } catch (PrivateControlLocator.PrivateControlUnavailableException exception) {
            return new TracingPolicyDeliveryResult(
                    TracingPolicyDeliveryOutcome.FAILED,
                    attemptId,
                    "CONTROL_PLANE_UNAVAILABLE",
                    expectedState.acceptedSequence());
        } catch (PrivateControlInvocationUnavailableException exception) {
            return new TracingPolicyDeliveryResult(
                    TracingPolicyDeliveryOutcome.FAILED,
                    attemptId,
                    "CONTROL_INVOCATION_UNAVAILABLE",
                    expectedState.acceptedSequence());
        }
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
        } catch (InstanceNotFoundException exception) {
            throw new PrivateControlInvocationUnavailableException(exception);
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

    private static final class PrivateControlInvocationUnavailableException
            extends RuntimeException {

        private PrivateControlInvocationUnavailableException(InstanceNotFoundException cause) {
            super(cause);
        }
    }
}
