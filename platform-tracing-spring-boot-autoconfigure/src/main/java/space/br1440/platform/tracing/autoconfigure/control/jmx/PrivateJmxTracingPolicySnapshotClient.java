package space.br1440.platform.tracing.autoconfigure.control.jmx;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeMBeanException;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.AcceptedCompleteSnapshotDecoder;
import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.PrivateControlRequestEncoder;
import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.PrivateControlResponse;
import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.control.policy.ExpectedTracingPolicyBase;
import space.br1440.platform.tracing.autoconfigure.control.policy.TracingPolicyContractViolationException;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicyDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicySnapshotApplier;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicySource;
import space.br1440.platform.tracing.autoconfigure.control.policy.read.AcceptedPolicyLeaves;
import space.br1440.platform.tracing.autoconfigure.control.policy.read.AcceptedPolicyProjection;
import space.br1440.platform.tracing.autoconfigure.control.policy.read.AcceptedSnapshotWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.policy.read.AcceptedTracingPolicyReader;
import space.br1440.platform.tracing.autoconfigure.control.policy.read.AgentReadinessView;
import space.br1440.platform.tracing.autoconfigure.control.policy.read.CompleteTracingPolicyReader;

public final class PrivateJmxTracingPolicySnapshotClient
        implements TracingPolicySnapshotApplier, CompleteTracingPolicyReader, AcceptedTracingPolicyReader {

    private static final Object[] NO_ARGS = new Object[0];
    private static final String[] NO_SIG = new String[0];

    private final PrivateControlLocator locator;

    private volatile MBeanServer privateServer;
    private volatile ObjectName controlObjectName;

    public PrivateJmxTracingPolicySnapshotClient(PrivateControlLocator locator) {
        this.locator = locator;
    }

    public void initialize() {
        try {
            PrivateControlLocator.LocatedPrivateControl located = locator.locate();
            this.privateServer = located.server();
            this.controlObjectName = located.writerObjectName();
        } catch (Exception e) {
            throw new TracingConfigurationGuardException("Cannot initialize snapshot client: private MBeanServer unavailable. " + e.getMessage());
        }
    }

    @Override
    public TracingPolicyDeliveryResult applySnapshot(ExpectedTracingPolicyBase expectedBase,
                                                     CompleteTracingPolicySnapshot snapshot,
                                                     TracingPolicySource source) {
        MBeanServer server = this.privateServer;
        ObjectName name = this.controlObjectName;
        if (server == null || name == null) {
            return TracingPolicyDeliveryResult.FAILED;
        }

        Objects.requireNonNull(expectedBase, "expectedBase");

        CompositeData encoded;
        try {
            encoded = PrivateControlRequestEncoder.encodeApplyRequest(
                    expectedBase,
                    UUID.randomUUID().toString(),
                    sourceAuthorityFor(source),
                    applyTriggerFor(source),
                    "",
                    snapshot
            );
        } catch (Exception e) {
            throw new TracingPolicyContractViolationException("Codec failed to encode snapshot — programming defect. source=" + source, e);
        }

        try {
            Object raw = server.invoke(
                    name, "applySnapshot",
                    new Object[]{encoded},
                    new String[]{"javax.management.openmbean.CompositeData"}
            );
            return parseOutcome(raw);
        } catch (TracingPolicyContractViolationException e) {
            throw e;
        } catch (MBeanException e) {
            Throwable cause = e.getTargetException();
            if (isContractCause(cause)) {
                throw new TracingPolicyContractViolationException("Agent contract violation in applySnapshot. source=" + source, cause);
            }

            return TracingPolicyDeliveryResult.FAILED;
        } catch (RuntimeMBeanException e) {
            RuntimeException cause = e.getTargetException();
            if (isContractCause(cause)) {
                throw new TracingPolicyContractViolationException("Agent RuntimeMBeanException — contract defect. source=" + source, cause);
            }

            return TracingPolicyDeliveryResult.FAILED;
        } catch (ReflectionException e) {
            throw new TracingPolicyContractViolationException("""
                    applySnapshot ReflectionException — JMX method not found or signature mismatch \
                    between App and Agent private control MBean. source=%s""".formatted(source), e);
        } catch (Exception e) {
            return TracingPolicyDeliveryResult.FAILED;
        }
    }

    @Override
    public Optional<AcceptedSnapshotWithSequence> currentComplete() {
        MBeanServer server = this.privateServer;
        ObjectName name = this.controlObjectName;
        if (server == null || name == null) {
            return Optional.empty();
        }

        try {
            Object result = server.invoke(name, "readAcceptedComplete", NO_ARGS, NO_SIG);
            if (result == null) {
                return Optional.empty();
            }

            AcceptedSnapshotWithSequence decoded = AcceptedCompleteSnapshotDecoder.decode(result);
            return Optional.of(decoded);
        } catch (TracingPolicyContractViolationException e) {
            throw e;
        } catch (MBeanException e) {
            Throwable cause = e.getTargetException();
            if (isContractCause(cause)) {
                throw new TracingPolicyContractViolationException("readAcceptedComplete MBeanException — contract defect", cause);
            }

            return Optional.empty();
        } catch (RuntimeMBeanException e) {
            RuntimeException cause = e.getTargetException();
            if (isContractCause(cause)) {
                throw new TracingPolicyContractViolationException("readAcceptedComplete RuntimeMBeanException — contract defect", cause);
            }

            return Optional.empty();
        } catch (ReflectionException e) {
            throw new TracingPolicyContractViolationException("readAcceptedComplete not found on Agent MBean — Agent/App version mismatch.", e);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<AcceptedPolicyProjection> currentProjection() {
        return currentComplete().map(this::toProjection);
    }

    private AcceptedPolicyProjection toProjection(AcceptedSnapshotWithSequence accepted) {
        CompleteTracingPolicySnapshot snapshot = accepted.snapshot();
        AcceptedPolicyLeaves leaves = new AcceptedPolicyLeaves(
                snapshot.samplingEnabled(),
                snapshot.samplingRatio(),
                snapshot.samplingRouteRatios().size(),
                snapshot.validationEnabled(),
                snapshot.validationStrict(),
                snapshot.exporterEnabled(),
                snapshot.traceControlHeadersEnabled(),
                snapshot.diagnosticsLogLevel()
        );

        AgentReadinessView readiness = new AgentReadinessView(
                accepted.runtimeInstanceId(),
                accepted.acceptedSequence(),
                true
        );

        return new AcceptedPolicyProjection(readiness, leaves);
    }

    private TracingPolicyDeliveryResult parseOutcome(Object raw) {
        if (raw instanceof CompositeData compositeData) {
            PrivateControlResponse response;
            try {
                response = PrivateControlResponse.decode(compositeData);
            } catch (IllegalArgumentException decodeFailure) {
                throw new TracingPolicyContractViolationException(
                        "applySnapshot response failed to decode — Agent/App contract violation",
                        decodeFailure
                );
            }

            try {
                return TracingPolicyDeliveryResult.valueOf(response.outcome());
            } catch (IllegalArgumentException ex) {
                throw new TracingPolicyContractViolationException("Unknown applySnapshot outcome: '" + response.outcome() + "' — Agent/App version mismatch");
            }
        }

        String type = (raw == null) ? "null" : raw.getClass().getName();
        throw new TracingPolicyContractViolationException("applySnapshot returned unexpected type: " + type);
    }

    private static String sourceAuthorityFor(TracingPolicySource source) {
        return switch (source) {
            case CONFIG_SERVER -> "CONFIG_SERVER";
            case ACTUATOR -> "DEV_ACTUATOR";
        };
    }

    private static String applyTriggerFor(TracingPolicySource source) {
        return switch (source) {
            case CONFIG_SERVER -> "REFRESH";
            case ACTUATOR -> "DEVELOPER_COMMAND";
        };
    }

    private static boolean isContractCause(Throwable t) {
        return t instanceof IllegalArgumentException
                || t instanceof IllegalStateException
                || t instanceof TracingPolicyContractViolationException;
    }
}
