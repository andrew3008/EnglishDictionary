package space.br1440.platform.tracing.autoconfigure.control.jmx;

import java.util.Objects;
import java.util.Optional;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.ReflectionException;
import javax.management.RuntimeMBeanException;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceException;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.AcceptedPolicySnapshotDecoder;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedTracingSettingsReader;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsContractViolationException;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxWireGraphValidator;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxWireTypeViolationException;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

final class JmxAcceptedSettingsReader implements AcceptedTracingSettingsReader {

    private final MBeanServer platformMBeanServer;
    private final AgentReadinessCoherenceGuard coherenceGuard;
    private final JmxWireGraphValidator wireValidator;
    private volatile TracingSettingsContractViolationException metadataFailure;

    JmxAcceptedSettingsReader(MBeanServer platformMBeanServer,
                              AgentReadinessCoherenceGuard coherenceGuard,
                              JmxWireGraphValidator wireValidator) {
        this.platformMBeanServer = Objects.requireNonNull(platformMBeanServer, "platformMBeanServer");
        this.coherenceGuard = Objects.requireNonNull(coherenceGuard, "coherenceGuard");
        this.wireValidator = Objects.requireNonNull(wireValidator, "wireValidator");
    }

    @Override
    public Optional<AcceptedSettings> current() {
        requireMetadataAvailable();

        try {
            return coherenceGuard.readCoherently(this::currentForStatusSnapshot);
        } catch (AgentReadinessCoherenceException coherenceFailure) {
            coherenceGuard.recordTrustFailure(coherenceFailure.failureCode());
            throw coherenceFailure;
        }
    }

    Optional<AcceptedSettings> currentForStatusSnapshot() {
        requireMetadataAvailable();
        AcceptedSettings accepted = decodeSnapshot(readSnapshotAttribute());
        coherenceGuard.requirePinnedAgentInstanceId(accepted.revision().agentInstanceId());
        return Optional.of(accepted);
    }

    void markMetadataUnavailable(Throwable cause) {
        metadataFailure = contractViolation(
                JmxClientFailureCode.METADATA_GATE_FAILED,
                "Accepted settings metadata validation failed",
                Objects.requireNonNull(cause, "cause")
        );
    }

    private void requireMetadataAvailable() {
        TracingSettingsContractViolationException unavailableMetadata = metadataFailure;
        if (unavailableMetadata != null) {
            throw unavailableMetadata;
        }
    }

    private Object readSnapshotAttribute() {
        try {
            return platformMBeanServer.getAttribute(
                    JmxWireContract.SETTINGS_STATUS_OBJECT_NAME,
                    JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT
            );
        } catch (AttributeNotFoundException attributeDrift) {
            throw contractViolation(
                    JmxClientFailureCode.STATUS_ATTRIBUTE_DRIFT,
                    "AcceptedPolicySnapshot attribute is unavailable",
                    attributeDrift
            );
        } catch (InstanceNotFoundException disappeared) {
            throw guardFailure(
                    JmxClientFailureCode.STATUS_ENDPOINT_DISAPPEARED,
                    "AcceptedPolicySnapshot endpoint disappeared after readiness was pinned",
                    disappeared
            );
        } catch (MBeanException providerFailure) {
            Throwable cause = providerFailure.getTargetException();
            throw contractViolation(
                    JmxClientFailureCode.STATUS_PROVIDER_INVOCATION_FAILED,
                    "AcceptedPolicySnapshot provider invocation failed",
                    (cause == null) ? providerFailure : cause
            );
        } catch (RuntimeMBeanException providerFailure) {
            RuntimeException cause = providerFailure.getTargetException();
            throw contractViolation(
                    JmxClientFailureCode.STATUS_PROVIDER_INVOCATION_FAILED,
                    "AcceptedPolicySnapshot provider invocation failed",
                    (cause == null) ? providerFailure : cause
            );
        } catch (ReflectionException attributeDrift) {
            throw contractViolation(
                    JmxClientFailureCode.STATUS_ATTRIBUTE_DRIFT,
                    "AcceptedPolicySnapshot attribute is incompatible",
                    attributeDrift
            );
        } catch (SecurityException securityFailure) {
            throw guardFailure(
                    JmxClientFailureCode.STATUS_TRANSPORT_SECURITY_FAILED,
                    "AcceptedPolicySnapshot transport access was denied",
                    securityFailure
            );
        } catch (Exception transportFailure) {
            throw guardFailure(
                    JmxClientFailureCode.STATUS_TRANSPORT_FAILED,
                    "AcceptedPolicySnapshot transport failed",
                    transportFailure
            );
        }
    }

    private AcceptedSettings decodeSnapshot(Object raw) {
        if (raw == null) {
            throw new TracingSettingsContractViolationException(
                    JmxClientFailureCode.STATUS_NULL_VALUE.message("AcceptedPolicySnapshot must not be null")
            );
        }

        try {
            wireValidator.validate(raw);
            return AcceptedPolicySnapshotDecoder.decode(raw);
        } catch (JmxWireTypeViolationException wireViolation) {
            throw contractViolation(
                    JmxClientFailureCode.STATUS_JMX_WIRE_TYPE_VIOLATION,
                    "AcceptedPolicySnapshot violates the neutral JMX wire contract",
                    wireViolation
            );
        } catch (IllegalArgumentException | IllegalStateException decodeFailure) {
            throw contractViolation(
                    JmxClientFailureCode.STATUS_RESPONSE_DECODE_FAILED,
                    "Cannot decode AcceptedPolicySnapshot",
                    decodeFailure
            );
        } catch (TracingSettingsContractViolationException contractFailure) {
            throw contractFailure;
        } catch (RuntimeException unexpectedFailure) {
            throw contractViolation(
                    JmxClientFailureCode.STATUS_RESPONSE_CONTRACT_FAILED,
                    "AcceptedPolicySnapshot response parsing failed unexpectedly",
                    unexpectedFailure
            );
        }
    }

    private static TracingSettingsContractViolationException contractViolation(JmxClientFailureCode failureCode,
                                                                                String detail,
                                                                                Throwable cause) {
        return new TracingSettingsContractViolationException(failureCode.message(detail), cause);
    }

    private static TracingConfigurationGuardException guardFailure(JmxClientFailureCode failureCode,
                                                                    String detail,
                                                                    Throwable cause) {
        return new TracingConfigurationGuardException(failureCode.message(detail), cause);
    }
}
