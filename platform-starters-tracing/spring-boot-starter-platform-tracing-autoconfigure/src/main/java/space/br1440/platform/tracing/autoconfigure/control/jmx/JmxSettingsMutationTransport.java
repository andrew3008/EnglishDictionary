package space.br1440.platform.tracing.autoconfigure.control.jmx;

import java.util.Objects;
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
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsDelivery;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsWriter;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsContractViolationException;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxWireGraphValidator;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxWireTypeViolationException;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

import lombok.extern.slf4j.Slf4j;

@Slf4j
final class JmxSettingsMutationTransport {

    private static final String OPERATION = JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT;

    private final MBeanServer mbeanServer;
    private final AgentReadinessCoherenceGuard coherenceGuard;
    private final JmxWireGraphValidator wireValidator;
    private final MutationFailureSink failures;

    JmxSettingsMutationTransport(MBeanServer mbeanServer,
                                 AgentReadinessCoherenceGuard coherenceGuard,
                                 JmxWireGraphValidator wireValidator,
                                 MutationFailureSink failures) {
        this.mbeanServer = Objects.requireNonNull(mbeanServer, "mbeanServer");
        this.coherenceGuard = Objects.requireNonNull(coherenceGuard, "coherenceGuard");
        this.wireValidator = Objects.requireNonNull(wireValidator, "wireValidator");
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    SettingsDelivery applySnapshot(String attemptId,
                                   SettingsRevision expectedBase,
                                   CompleteTracingSettings snapshot,
                                   SettingsWriter writer) {
        Attempt attempt = new Attempt(attemptId, expectedBase, writer);
        CompositeData request = encode(attempt, Objects.requireNonNull(snapshot, "snapshot"));
        try {
            SettingsDelivery delivery = coherenceGuard.readCoherently(() -> exchange(attempt, request));
            log.info("Tracing settings mutation completed: {}, outcome={}, messageCode={}",
                    attempt, delivery.outcome(), delivery.messageCode());
            return delivery;
        } catch (AgentReadinessCoherenceException coherenceFailure) {
            failures.publishTerminalFailure(coherenceFailure.failureCode(), coherenceFailure);
            throw coherenceFailure;
        }
    }

    private SettingsDelivery exchange(Attempt attempt, CompositeData request) {
        Object raw = invoke(attempt, request);
        return readOutcome(attempt, raw);
    }

    private Object invoke(Attempt attempt, CompositeData composite) {
        try {
            return mbeanServer.invoke(
                    JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME,
                    JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT,
                    new Object[]{composite},
                    new String[]{CompositeData.class.getName()}
            );
        } catch (InstanceNotFoundException endpointDisappeared) {
            throw failures.terminalGuardFailure(
                    JmxClientFailureCode.MUTATION_ENDPOINT_DISAPPEARED,
                    attempt.describe("endpoint disappeared after readiness was pinned"),
                    endpointDisappeared
            );
        } catch (MBeanException providerFailure) {
            throw failures.terminalGuardFailure(
                    JmxClientFailureCode.MUTATION_PROVIDER_INVOCATION_FAILED,
                    attempt.describe("provider invocation failed"),
                    targetOrSelf(providerFailure, providerFailure.getTargetException())
            );
        } catch (RuntimeMBeanException providerFailure) {
            throw failures.terminalGuardFailure(
                    JmxClientFailureCode.MUTATION_PROVIDER_INVOCATION_FAILED,
                    attempt.describe("provider invocation failed"),
                    targetOrSelf(providerFailure, providerFailure.getTargetException())
            );
        } catch (ReflectionException signatureDrift) {
            throw failures.terminalContractViolation(
                    JmxClientFailureCode.MUTATION_SIGNATURE_DRIFT,
                    attempt.describe("operation is unavailable or incompatible"),
                    signatureDrift
            );
        } catch (SecurityException accessDenied) {
            throw failures.terminalGuardFailure(
                    JmxClientFailureCode.MUTATION_TRANSPORT_SECURITY_FAILED,
                    attempt.describe("transport access was denied"),
                    accessDenied
            );
        } catch (Exception transportFailure) {
            throw failures.terminalGuardFailure(
                    JmxClientFailureCode.MUTATION_TRANSPORT_FAILED,
                    attempt.describe("transport failed"),
                    transportFailure
            );
        }
    }

    private SettingsDelivery readOutcome(Attempt attempt, Object raw) {
        try {
            wireValidator.validate(raw);
            return decodeOutcome(attempt, raw);
        } catch (JmxWireTypeViolationException wireViolation) {
            throw failures.terminalContractViolation(
                    JmxClientFailureCode.MUTATION_JMX_WIRE_TYPE_VIOLATION,
                    attempt.describe("response violates the neutral JMX wire contract"),
                    wireViolation
            );
        } catch (TracingSettingsContractViolationException contractFailure) {
            failures.invalidateAndNotify(JmxClientFailureCode.MUTATION_RESPONSE_CONTRACT_FAILED, contractFailure);
            throw contractFailure;
        } catch (AgentReadinessCoherenceException coherenceFailure) {
            throw coherenceFailure;
        } catch (RuntimeException unexpectedFailure) {
            throw failures.terminalGuardFailure(
                    JmxClientFailureCode.MUTATION_UNEXPECTED_ERROR,
                    attempt.describe("response parsing failed unexpectedly"),
                    unexpectedFailure
            );
        }
    }

    private SettingsDelivery decodeOutcome(Attempt attempt, Object raw) {
        if (!(raw instanceof CompositeData composite)) {
            String type = (raw == null) ? "null" : raw.getClass().getName();
            throw contractViolation(
                    JmxClientFailureCode.MUTATION_RESPONSE_TYPE_INVALID,
                    attempt.describe("returned " + type),
                    null
            );
        }

        PlatformJmxResponse response = decodeCorrelated(attempt, composite);
        return new SettingsDelivery(
                attempt.attemptId(),
                toDeliveryResult(attempt, response),
                response.messageCode().name()
        );
    }

    private PlatformJmxResponse decodeCorrelated(Attempt attempt, CompositeData composite) {
        try {
            PlatformJmxResponse response = PlatformJmxResponse.decode(composite);
            coherenceGuard.requirePinnedAgentInstanceId(response.agentInstanceId());
            response.validateCorrelation(
                    attempt.expectedBase().agentInstanceId(),
                    attempt.expectedBase().acceptedSequence(),
                    attempt.attemptId()
            );
            return response;
        } catch (IllegalArgumentException decodeFailure) {
            throw contractViolation(
                    JmxClientFailureCode.MUTATION_RESPONSE_DECODE_FAILED,
                    attempt.describe("cannot decode response"),
                    decodeFailure
            );
        }
    }

    private static TracingSettingsDeliveryResult toDeliveryResult(Attempt attempt, PlatformJmxResponse response) {
        try {
            return TracingSettingsDeliveryResult.valueOf(response.outcome().name());
        } catch (IllegalArgumentException unknownOutcome) {
            throw contractViolation(
                    JmxClientFailureCode.MUTATION_OUTCOME_UNKNOWN,
                    attempt.describe("unknown outcome '" + response.outcome() + "'"),
                    unknownOutcome
            );
        }
    }

    private static CompositeData encode(Attempt attempt, CompleteTracingSettings snapshot) {
        try {
            return PlatformJmxRequestEncoder.encodeApplyRequest(
                    ApplySnapshotRequestParameters.builder()
                            .expectedBase(attempt.expectedBase())
                            .attemptId(attempt.attemptId())
                            .writer(attempt.writer().name())
                            .policy(snapshot)
                            .build()
            );
        } catch (Exception encodingFailure) {
            throw contractViolation(
                    JmxClientFailureCode.MUTATION_ENCODING_FAILED,
                    attempt.describe("cannot encode request"),
                    encodingFailure
            );
        }
    }

    private static TracingSettingsContractViolationException contractViolation(JmxClientFailureCode code,
                                                                               String message,
                                                                               Throwable cause) {
        return (cause == null)
                ? new TracingSettingsContractViolationException(code.message(message))
                : new TracingSettingsContractViolationException(code.message(message), cause);
    }

    private static Throwable targetOrSelf(Exception wrapper, Throwable target) {
        return (target == null) ? wrapper : target;
    }

    private record Attempt(String attemptId, SettingsRevision expectedBase, SettingsWriter writer) {

        Attempt {
            Objects.requireNonNull(attemptId, "attemptId");
            if (attemptId.isBlank()) {
                throw new IllegalArgumentException("attemptId must not be blank");
            }

            Objects.requireNonNull(expectedBase, "expectedBase");
            Objects.requireNonNull(writer, "writer");
        }

        String describe(String what) {
            return OPERATION + " " + what + ". " + this;
        }

        @Override
        public String toString() {
            return "attemptId=" + attemptId + ", writer=" + writer + ", base=" + expectedBase;
        }
    }
}
