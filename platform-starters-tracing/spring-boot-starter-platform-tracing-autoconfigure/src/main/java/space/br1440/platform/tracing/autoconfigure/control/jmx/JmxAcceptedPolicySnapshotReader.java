package space.br1440.platform.tracing.autoconfigure.control.jmx;

import java.util.Objects;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.ReflectionException;
import javax.management.RuntimeMBeanException;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceException;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.AcceptedPolicySnapshotDecoder;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsContractViolationException;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsWithSequence;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxWireGraphValidator;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxWireTypeViolationException;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

final class JmxAcceptedPolicySnapshotReader {

    private final MBeanServer platformMBeanServer;
    private final AgentReadinessCoherenceGuard coherenceGuard;
    private final JmxWireGraphValidator wireValidator;
    private final PlatformJmxTracingSettingsClient client;

    JmxAcceptedPolicySnapshotReader(MBeanServer platformMBeanServer,
                                    AgentReadinessCoherenceGuard coherenceGuard,
                                    JmxWireGraphValidator wireValidator,
                                    PlatformJmxTracingSettingsClient client) {
        this.platformMBeanServer = Objects.requireNonNull(platformMBeanServer, "platformMBeanServer");
        this.coherenceGuard = Objects.requireNonNull(coherenceGuard, "coherenceGuard");
        this.wireValidator = Objects.requireNonNull(wireValidator, "wireValidator");
        this.client = Objects.requireNonNull(client, "client");
    }

    AcceptedSettingsWithSequence readAcceptedPolicy() {
        Object raw = readSnapshotAttribute();
        return decodeSnapshot(raw);
    }

    private Object readSnapshotAttribute() {
        try {
            return coherenceGuard.readCoherently(() -> platformMBeanServer.getAttribute(
                    JmxWireContract.SETTINGS_STATUS_OBJECT_NAME,
                    JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT)
            );
        } catch (AgentReadinessCoherenceException coherenceFailure) {
            client.publishTerminalFailure(coherenceFailure.failureCode(), coherenceFailure);
            throw coherenceFailure;
        } catch (AttributeNotFoundException attributeDrift) {
            throw client.terminalContractViolation(
                    JmxClientFailureCode.STATUS_ATTRIBUTE_DRIFT,
                    "AcceptedPolicySnapshot attribute is unavailable",
                    attributeDrift);
        } catch (InstanceNotFoundException disappeared) {
            throw client.terminalGuardFailure(
                    JmxClientFailureCode.STATUS_ENDPOINT_DISAPPEARED,
                    "AcceptedPolicySnapshot endpoint disappeared after readiness was pinned",
                    disappeared);
        } catch (MBeanException e) {
            Throwable cause = e.getTargetException();
            throw client.terminalGuardFailure(
                    JmxClientFailureCode.STATUS_PROVIDER_INVOCATION_FAILED,
                    "AcceptedPolicySnapshot provider invocation failed",
                    (cause == null) ? e : cause);
        } catch (RuntimeMBeanException e) {
            RuntimeException cause = e.getTargetException();
            throw client.terminalGuardFailure(
                    JmxClientFailureCode.STATUS_PROVIDER_INVOCATION_FAILED,
                    "AcceptedPolicySnapshot provider invocation failed",
                    cause == null ? e : cause);
        } catch (ReflectionException e) {
            throw client.terminalContractViolation(
                    JmxClientFailureCode.STATUS_ATTRIBUTE_DRIFT,
                    "AcceptedPolicySnapshot attribute is incompatible",
                    e);
        } catch (SecurityException securityFailure) {
            throw client.terminalGuardFailure(
                    JmxClientFailureCode.STATUS_TRANSPORT_SECURITY_FAILED,
                    "AcceptedPolicySnapshot transport access was denied",
                    securityFailure);
        } catch (Exception transportFailure) {
            throw client.terminalGuardFailure(
                    JmxClientFailureCode.STATUS_TRANSPORT_FAILED,
                    "AcceptedPolicySnapshot transport failed",
                    transportFailure);
        }
    }
    private AcceptedSettingsWithSequence decodeSnapshot(Object raw) {
        try {
            if (raw == null) {
                throw new TracingSettingsContractViolationException(
                        JmxClientFailureCode.STATUS_NULL_VALUE.message("AcceptedPolicySnapshot must not be null")
                );
            }

            wireValidator.validate(raw);
            return AcceptedPolicySnapshotDecoder.decode(raw);
        } catch (JmxWireTypeViolationException wireViolation) {
            throw client.terminalContractViolation(
                    JmxClientFailureCode.STATUS_JMX_WIRE_TYPE_VIOLATION,
                    "AcceptedPolicySnapshot violates the neutral JMX wire contract",
                    wireViolation
                );
        } catch (TracingSettingsContractViolationException e) {
            client.invalidateAndNotify(JmxClientFailureCode.STATUS_RESPONSE_CONTRACT_FAILED, e);
            throw e;
        } catch (IllegalArgumentException | IllegalStateException decodeFailure) {
            throw client.terminalContractViolation(
                    JmxClientFailureCode.STATUS_RESPONSE_DECODE_FAILED,
                    "Cannot decode AcceptedPolicySnapshot",
                    decodeFailure
                );
        } catch (RuntimeException unexpectedFailure) {
            throw client.terminalGuardFailure(
                    JmxClientFailureCode.STATUS_TRANSPORT_FAILED,
                    "AcceptedPolicySnapshot response parsing failed unexpectedly",
                    unexpectedFailure
                );
        }
    }
}
