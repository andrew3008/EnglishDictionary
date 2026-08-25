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
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsContractViolationException;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsApplier;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliverySource;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsLeaves;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsReadModel;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedTracingSettingsReader;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AgentReadinessSnapshot;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.CompleteTracingSettingsReader;

public final class PrivateJmxTracingSettingsClient
        implements TracingSettingsApplier, CompleteTracingSettingsReader, AcceptedTracingSettingsReader {

    private static final Object[] NO_ARGS = new Object[0];
    private static final String[] NO_SIG = new String[0];

    private final PrivateControlLocator locator;

    private volatile MBeanServer privateServer;
    private volatile ObjectName controlObjectName;

    public PrivateJmxTracingSettingsClient(PrivateControlLocator locator) {
        this.locator = locator;
    }

    public void initialize() {
        try {
            PrivateControlLocator.LocatedPrivateControl located = locator.locate();
            this.privateServer = located.server();
            this.controlObjectName = located.writerObjectName();
        } catch (Exception e) {
            throw new TracingConfigurationGuardException("Cannot initialize snapshot client: private MBeanServer unavailable", e);
        }
    }

    @Override
    public TracingSettingsDeliveryResult applySnapshot(ExpectedSettingsRevision expectedBase,
                                                     CompleteTracingSettings snapshot,
                                                     TracingSettingsDeliverySource source) {
        MBeanServer server = this.privateServer;
        ObjectName name = this.controlObjectName;
        if (server == null || name == null) {
            return TracingSettingsDeliveryResult.FAILED;
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
            throw new TracingSettingsContractViolationException("Codec failed to encode snapshot — programming defect. source=" + source, e);
        }

        try {
            Object raw = server.invoke(
                    name, "applySnapshot",
                    new Object[]{encoded},
                    new String[]{"javax.management.openmbean.CompositeData"}
            );
            return parseOutcome(raw);
        } catch (TracingSettingsContractViolationException e) {
            throw e;
        } catch (MBeanException e) {
            Throwable cause = e.getTargetException();
            if (isContractCause(cause)) {
                throw new TracingSettingsContractViolationException("Agent contract violation in applySnapshot. source=" + source, cause);
            }

            return TracingSettingsDeliveryResult.FAILED;
        } catch (RuntimeMBeanException e) {
            RuntimeException cause = e.getTargetException();
            if (isContractCause(cause)) {
                throw new TracingSettingsContractViolationException("Agent RuntimeMBeanException — contract defect. source=" + source, cause);
            }

            return TracingSettingsDeliveryResult.FAILED;
        } catch (ReflectionException e) {
            throw new TracingSettingsContractViolationException("""
                    applySnapshot ReflectionException — JMX method not found or signature mismatch \
                    between App and Agent private control MBean. source=%s""".formatted(source), e);
        } catch (Exception e) {
            return TracingSettingsDeliveryResult.FAILED;
        }
    }

    @Override
    public Optional<AcceptedSettingsWithSequence> currentComplete() {
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

            AcceptedSettingsWithSequence decoded = AcceptedCompleteSnapshotDecoder.decode(result);
            return Optional.of(decoded);
        } catch (TracingSettingsContractViolationException e) {
            throw e;
        } catch (MBeanException e) {
            Throwable cause = e.getTargetException();
            if (isContractCause(cause)) {
                throw new TracingSettingsContractViolationException("readAcceptedComplete MBeanException — contract defect", cause);
            }

            return Optional.empty();
        } catch (RuntimeMBeanException e) {
            RuntimeException cause = e.getTargetException();
            if (isContractCause(cause)) {
                throw new TracingSettingsContractViolationException("readAcceptedComplete RuntimeMBeanException — contract defect", cause);
            }

            return Optional.empty();
        } catch (ReflectionException e) {
            throw new TracingSettingsContractViolationException("readAcceptedComplete not found on Agent MBean — Agent/App version mismatch.", e);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<AcceptedSettingsReadModel> currentReadModel() {
        return currentComplete().map(this::toReadModel);
    }

    private AcceptedSettingsReadModel toReadModel(AcceptedSettingsWithSequence accepted) {
        CompleteTracingSettings snapshot = accepted.snapshot();
        AcceptedSettingsLeaves leaves = new AcceptedSettingsLeaves(
                snapshot.samplingEnabled(),
                snapshot.samplingRatio(),
                snapshot.samplingRouteRatios().size(),
                snapshot.validationEnabled(),
                snapshot.validationStrict(),
                snapshot.exporterEnabled(),
                snapshot.traceControlHeadersEnabled(),
                snapshot.diagnosticsLogLevel()
        );

        AgentReadinessSnapshot readiness = new AgentReadinessSnapshot(
                accepted.runtimeInstanceId(),
                accepted.acceptedSequence(),
                true
        );

        return new AcceptedSettingsReadModel(readiness, leaves);
    }

    private TracingSettingsDeliveryResult parseOutcome(Object raw) {
        if (raw instanceof CompositeData compositeData) {
            PrivateControlResponse response;
            try {
                response = PrivateControlResponse.decode(compositeData);
            } catch (IllegalArgumentException decodeFailure) {
                throw new TracingSettingsContractViolationException(
                        "applySnapshot response failed to decode — Agent/App contract violation",
                        decodeFailure
                );
            }

            try {
                return TracingSettingsDeliveryResult.valueOf(response.outcome());
            } catch (IllegalArgumentException ex) {
                throw new TracingSettingsContractViolationException("Unknown applySnapshot outcome: '" + response.outcome() + "' — Agent/App version mismatch");
            }
        }

        String type = (raw == null) ? "null" : raw.getClass().getName();
        throw new TracingSettingsContractViolationException("applySnapshot returned unexpected type: " + type);
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

    private static boolean isContractCause(Throwable t) {
        return t instanceof IllegalArgumentException
                || t instanceof IllegalStateException
                || t instanceof TracingSettingsContractViolationException;
    }
}
