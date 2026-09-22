package space.br1440.platform.tracing.autoconfigure.control.jmx;

import java.util.Objects;
import java.util.UUID;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.ReflectionException;
import javax.management.RuntimeMBeanException;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceException;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.ApplySnapshotRequestParameters;
import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.PlatformJmxRequestEncoder;
import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.PlatformJmxResponse;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsContractViolationException;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliverySource;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxWireGraphValidator;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxWireTypeViolationException;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

final class JmxSettingsMutationTransport {

    private final MBeanServer platformMBeanServer;
    private final AgentReadinessCoherenceGuard coherenceGuard;
    private final JmxWireGraphValidator wireValidator;
    private final PlatformJmxTracingSettingsClient client;

    JmxSettingsMutationTransport(MBeanServer platformMBeanServer,
                                 AgentReadinessCoherenceGuard coherenceGuard,
                                 JmxWireGraphValidator wireValidator,
                                 PlatformJmxTracingSettingsClient client) {
        this.platformMBeanServer = Objects.requireNonNull(platformMBeanServer, "platformMBeanServer");
        this.coherenceGuard = Objects.requireNonNull(coherenceGuard, "coherenceGuard");
        this.wireValidator = Objects.requireNonNull(wireValidator, "wireValidator");
        this.client = Objects.requireNonNull(client, "client");
    }

    TracingSettingsDeliveryResult applySnapshot(ExpectedSettingsRevision expectedBase,
                                                CompleteTracingSettings snapshot,
                                                TracingSettingsDeliverySource source) {
        Objects.requireNonNull(expectedBase, "expectedBase");

        EncodedRequest request = encodeRequest(expectedBase, snapshot, source);
        Object raw = invokeMutation(request.composite(), source);
        return readOutcome(raw, expectedBase, request);
    }

    private Object invokeMutation(CompositeData encoded, TracingSettingsDeliverySource source) {
        try {
            return coherenceGuard.readCoherently(() -> platformMBeanServer.invoke(
                    JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME,
                    JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT,
                    new Object[]{encoded},
                    new String[]{CompositeData.class.getName()}
            ));
        } catch (AgentReadinessCoherenceException coherenceFailure) {
            client.publishTerminalFailure(coherenceFailure.failureCode(), coherenceFailure);
            throw coherenceFailure;
        } catch (InstanceNotFoundException disappearedEndpoint) {
            throw client.terminalGuardFailure(
                    JmxClientFailureCode.MUTATION_ENDPOINT_DISAPPEARED,
                    "applySnapshot endpoint disappeared after readiness was pinned",
                    disappearedEndpoint
            );
        } catch (MBeanException ex) {
            Throwable cause = ex.getTargetException();
            throw client.terminalGuardFailure(
                    JmxClientFailureCode.MUTATION_PROVIDER_INVOCATION_FAILED,
                    "applySnapshot provider invocation failed. source=" + source,
                    (cause == null) ? ex : cause
            );
        } catch (RuntimeMBeanException ex) {
            RuntimeException cause = ex.getTargetException();
            throw client.terminalGuardFailure(
                    JmxClientFailureCode.MUTATION_PROVIDER_INVOCATION_FAILED,
                    "applySnapshot provider invocation failed. source=" + source,
                    (cause == null) ? ex : cause
            );
        } catch (ReflectionException ex) {
            throw client.terminalContractViolation(
                    JmxClientFailureCode.MUTATION_SIGNATURE_DRIFT,
                    "applySnapshot operation is unavailable or incompatible. source=" + source,
                    ex
            );
        } catch (SecurityException securityFailure) {
            throw client.terminalGuardFailure(
                    JmxClientFailureCode.MUTATION_TRANSPORT_SECURITY_FAILED,
                    "applySnapshot transport access was denied. source=" + source,
                    securityFailure
            );
        } catch (Exception transportFailure) {
            throw client.terminalGuardFailure(
                    JmxClientFailureCode.MUTATION_TRANSPORT_FAILED,
                    "applySnapshot transport failed. source=" + source,
                    transportFailure
            );
        }
    }

    private TracingSettingsDeliveryResult readOutcome(Object raw,
                                                       ExpectedSettingsRevision expectedBase,
                                                       EncodedRequest request) {
        try {
            wireValidator.validate(raw);
            return parseOutcome(raw, expectedBase, request);
        } catch (JmxWireTypeViolationException wireViolation) {
            throw client.terminalContractViolation(
                    JmxClientFailureCode.MUTATION_JMX_WIRE_TYPE_VIOLATION,
                    "applySnapshot response violates the neutral JMX wire contract",
                    wireViolation
            );
        } catch (TracingSettingsContractViolationException ex) {
            client.invalidateAndNotify(JmxClientFailureCode.MUTATION_RESPONSE_CONTRACT_FAILED, ex);
            throw ex;
        } catch (RuntimeException unexpectedFailure) {
            throw client.terminalGuardFailure(
                    JmxClientFailureCode.MUTATION_UNEXPECTED_ERROR,
                    "applySnapshot response parsing failed unexpectedly",
                    unexpectedFailure
            );
        }
    }

    private record EncodedRequest(String attemptId, CompositeData composite) {
    }

    private static EncodedRequest encodeRequest(ExpectedSettingsRevision expectedBase,
                                                CompleteTracingSettings snapshot,
                                                TracingSettingsDeliverySource source) {
        String attemptId = UUID.randomUUID().toString();
        try {
            CompositeData composite = PlatformJmxRequestEncoder.encodeApplyRequest(
                    ApplySnapshotRequestParameters.builder()
                            .expectedBase(expectedBase)
                            .attemptId(attemptId)
                            .sourceAuthority(sourceAuthorityFor(source))
                            .applyTrigger(applyTriggerFor(source))
                            // sourceRevision — зарезервированное wire-поле для будущей корреляции
                            // с версией манифеста (например, Config Server revision). У Spring
                            // сегодня нет источника этого значения ни для CONFIG_SERVER, ни для
                            // ACTUATOR, поэтому оно всегда пустое; на domain validation/apply не влияет.
                            .sourceRevision("")
                            .policy(snapshot)
                            .build()
            );
            return new EncodedRequest(attemptId, composite);
        } catch (Exception ex) {
            String message = "Cannot encode applySnapshot request. source=" + source;
            throw new TracingSettingsContractViolationException(
                    JmxClientFailureCode.MUTATION_ENCODING_FAILED.message(message),
                    ex
            );
        }
    }

    private static TracingSettingsDeliveryResult parseOutcome(Object raw,
                                                              ExpectedSettingsRevision expectedBase,
                                                              EncodedRequest request) {
        if (!(raw instanceof CompositeData compositeData)) {
            String type = (raw == null) ? "null" : raw.getClass().getName();
            throw new TracingSettingsContractViolationException(
                    JmxClientFailureCode.MUTATION_RESPONSE_TYPE_INVALID.message("applySnapshot returned " + type)
            );
        }

        String candidateDigest = (String) request.composite()
                .get(JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_CANDIDATE_DIGEST);

        PlatformJmxResponse response;
        try {
            response = PlatformJmxResponse.decode(compositeData);
            response.validateCorrelation(
                    expectedBase.runtimeInstanceId(),
                    expectedBase.acceptedSequence(),
                    request.attemptId(),
                    candidateDigest
            );
        } catch (IllegalArgumentException decodeFailure) {
            throw new TracingSettingsContractViolationException(
                    JmxClientFailureCode.MUTATION_RESPONSE_DECODE_FAILED.message("Cannot decode applySnapshot response"),
                    decodeFailure
            );
        }

        try {
            return TracingSettingsDeliveryResult.valueOf(response.outcome().name());
        } catch (IllegalArgumentException ex) {
            String message = "Unknown applySnapshot outcome '" + response.outcome() + "'";
            throw new TracingSettingsContractViolationException(
                    JmxClientFailureCode.MUTATION_OUTCOME_UNKNOWN.message(message)
            );
        }
    }

    private static String sourceAuthorityFor(TracingSettingsDeliverySource source) {
        return switch (source) {
            case CONFIG_SERVER -> "CONFIG_SERVER";
            case ACTUATOR -> "DEV_ACTUATOR";
        };
    }

    private static String applyTriggerFor(TracingSettingsDeliverySource source) {
        return switch (source) {
            case CONFIG_SERVER -> "REFRESH";
            case ACTUATOR -> "DEVELOPER_COMMAND";
        };
    }
}
