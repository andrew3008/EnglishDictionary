package space.br1440.platform.tracing.autoconfigure.control.jmx;

import java.lang.management.ManagementFactory;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceException;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsDelivery;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsWriter;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsContractViolationException;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsWriter;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanInfoShapeViolationException;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxWireGraphValidator;
import space.br1440.platform.tracing.autoconfigure.status.AcceptedSettingsStatusReader;

public final class PlatformJmxTracingSettingsClient implements TracingSettingsWriter {

    private final AgentReadinessCoherenceGuard coherenceGuard;
    private final AtomicBoolean metadataGatePassed = new AtomicBoolean();
    private final JmxSettingsMetadataGate metadataGate;
    private final JmxSettingsMutationTransport mutationTransport;
    private final JmxAcceptedSettingsReader acceptedSettingsReader;
    private final FailureSink failureSink = new FailureSink();

    PlatformJmxTracingSettingsClient(AgentReadinessCoherenceGuard coherenceGuard) {
        this(ManagementFactory.getPlatformMBeanServer(), coherenceGuard);
    }

    public PlatformJmxTracingSettingsClient(MBeanServer platformMBeanServer,
                                            AgentReadinessCoherenceGuard coherenceGuard) {
        Objects.requireNonNull(platformMBeanServer, "platformMBeanServer");
        this.coherenceGuard = Objects.requireNonNull(coherenceGuard, "coherenceGuard");

        JmxWireGraphValidator wireValidator = new JmxWireGraphValidator();
        this.metadataGate = new JmxSettingsMetadataGate(platformMBeanServer, coherenceGuard);
        this.mutationTransport = new JmxSettingsMutationTransport(
                platformMBeanServer,
                coherenceGuard,
                wireValidator,
                failureSink
        );

        this.acceptedSettingsReader = new JmxAcceptedSettingsReader(
                platformMBeanServer,
                coherenceGuard,
                wireValidator
        );
    }

    public void initialize() {
        requireTrusted();
        metadataGatePassed.set(false);

        try {
            metadataGate.verifyContractMetadata();
            metadataGatePassed.set(true);
        } catch (AgentReadinessCoherenceException coherenceFailure) {
            throw foreignGuardFailure(
                    coherenceFailure.failureCode(),
                    "Platform tracing MBean metadata was read outside the pinned readiness generation",
                    coherenceFailure
            );
        } catch (JmxMBeanInfoShapeViolationException incompatibleMetadata) {
            throw terminalGuardFailure(
                    JmxClientFailureCode.METADATA_GATE_FAILED,
                    "Platform tracing MBean metadata is incompatible",
                    incompatibleMetadata
            );
        } catch (InstanceNotFoundException disappearedEndpoint) {
            throw terminalGuardFailure(
                    JmxClientFailureCode.METADATA_ENDPOINT_DISAPPEARED,
                    "Platform tracing MBean metadata endpoint disappeared after readiness was pinned",
                    disappearedEndpoint
            );
        } catch (SecurityException securityFailure) {
            throw terminalGuardFailure(
                    JmxClientFailureCode.METADATA_TRANSPORT_SECURITY_FAILED,
                    "Platform tracing MBean metadata access was denied",
                    securityFailure
            );
        } catch (Exception unavailableEndpoint) {
            throw terminalGuardFailure(
                    JmxClientFailureCode.METADATA_GATE_FAILED,
                    "Platform tracing MBean metadata is unavailable",
                    unavailableEndpoint
            );
        }
    }

    @Override
    public SettingsDelivery applySnapshot(SettingsRevision expectedBase,
                                          CompleteTracingSettings snapshot,
                                          SettingsWriter writer) {
        String attemptId = UUID.randomUUID().toString();
        requireTrusted();
        if (!metadataGatePassed.get()) {
            return failed(attemptId, JmxClientFailureCode.METADATA_GATE_FAILED);
        }

        return mutationTransport.applySnapshot(attemptId, expectedBase, snapshot, writer);
    }

    @SuppressWarnings("SameParameterValue")
    private static SettingsDelivery failed(String attemptId, JmxClientFailureCode failureCode) {
        return new SettingsDelivery(
                attemptId,
                TracingSettingsDeliveryResult.FAILED,
                failureCode.token()
        );
    }

    @Override
    public Optional<AcceptedSettings> current() {
        return acceptedSettingsReader.current();
    }

    AcceptedSettingsStatusReader statusReader() {
        return acceptedSettingsReader::currentForStatusSnapshot;
    }

    MutationFailureSink failureSink() {
        return failureSink;
    }

    void requireTrusted() {
        String failureCode = coherenceGuard.terminalFailureCode();
        if (failureCode != null) {
            String detail = "Platform tracing client trust was terminally invalidated by " + failureCode;
            String message = JmxClientFailureCode.TERMINALLY_INVALIDATED.message(detail);
            throw new TracingConfigurationGuardException(message);
        }
    }

    void publishTerminalFailure(String failureCode, Throwable primaryFailure) {
        Objects.requireNonNull(failureCode, "failureCode");
        Objects.requireNonNull(primaryFailure, "primaryFailure");

        try {
            coherenceGuard.recordTrustFailure(failureCode);
        } catch (RuntimeException latchFailure) {
            primaryFailure.addSuppressed(latchFailure);
        }
    }

    TracingSettingsContractViolationException terminalContractViolation(JmxClientFailureCode failureCode,
                                                                        String detail,
                                                                        Throwable cause) {
        String message = failureCode.message(detail);
        TracingSettingsContractViolationException failure = new TracingSettingsContractViolationException(message, cause);
        invalidateAndNotify(failureCode, failure);
        return failure;
    }

    TracingConfigurationGuardException terminalGuardFailure(JmxClientFailureCode failureCode,
                                                            String detail,
                                                            Throwable cause) {
        String message = failureCode.message(detail);
        TracingConfigurationGuardException failure = new TracingConfigurationGuardException(message, cause);
        invalidateAndNotify(failureCode, failure);
        return failure;
    }

    @SuppressWarnings("SameParameterValue")
    TracingConfigurationGuardException foreignGuardFailure(String foreignFailureCode,
                                                           String detail,
                                                           Throwable cause) {
        String message = foreignFailureCode + ": " + detail;
        TracingConfigurationGuardException failure = new TracingConfigurationGuardException(message, cause);
        publishTerminalFailure(foreignFailureCode, failure);
        return failure;
    }

    void invalidateAndNotify(JmxClientFailureCode failureCode,
                             Throwable primaryFailure) {
        String failureToken = Objects.requireNonNull(failureCode, "failureCode").token();
        publishTerminalFailure(failureToken, primaryFailure);
    }

    private final class FailureSink implements MutationFailureSink {

        @Override
        public TracingConfigurationGuardException terminalGuardFailure(JmxClientFailureCode code,
                                                                       String message,
                                                                       Throwable cause) {
            return PlatformJmxTracingSettingsClient.this.terminalGuardFailure(code, message, cause);
        }

        @Override
        public TracingSettingsContractViolationException terminalContractViolation(JmxClientFailureCode code,
                                                                                   String message,
                                                                                   Throwable cause) {
            return PlatformJmxTracingSettingsClient.this.terminalContractViolation(code, message, cause);
        }

        @Override
        public void invalidateAndNotify(JmxClientFailureCode code, Throwable primaryFailure) {
            PlatformJmxTracingSettingsClient.this.invalidateAndNotify(code, primaryFailure);
        }

        @Override
        public void publishTerminalFailure(String failureCode, Throwable primaryFailure) {
            PlatformJmxTracingSettingsClient.this.publishTerminalFailure(failureCode, primaryFailure);
        }
    }
}
