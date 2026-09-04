package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeErrorException;
import javax.management.RuntimeMBeanException;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanInfoShape;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanInfoShapeViolationException;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxWireGraphValidator;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxWireTypeViolationException;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

final class AgentReadinessProbe {

    static final String READINESS_ATTRIBUTE = JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT;

    private final MBeanServer server;
    private final JmxWireGraphValidator wireValidator;

    AgentReadinessProbe(MBeanServer server) {
        this.server = Objects.requireNonNull(server, "server");
        this.wireValidator = new JmxWireGraphValidator();
    }

    boolean endpointRegistered(ObjectName readinessName) {
        return server.isRegistered(readinessName);
    }

    ProbeResult read(ObjectName readinessName) {
        Object raw;
        try {
            MBeanInfo metadata = server.getMBeanInfo(readinessName);
            JmxMBeanInfoShape.requireReadOnlySnapshot(
                    metadata,
                    READINESS_ATTRIBUTE,
                    JmxOpenTypes.READINESS_SNAPSHOT
            );
            raw = server.getAttribute(readinessName, READINESS_ATTRIBUTE);
            wireValidator.validate(raw);
        } catch (InstanceNotFoundException disappeared) {
            return new ProbeResult.Rejected(ProbeReason.ENDPOINT_DISAPPEARED);
        } catch (JmxMBeanInfoShapeViolationException | AttributeNotFoundException incompatibleMetadata) {
            return new ProbeResult.Rejected(ProbeReason.INCOMPATIBLE_METADATA);
        } catch (JmxWireTypeViolationException wireViolation) {
            return new ProbeResult.Rejected(ProbeReason.JMX_WIRE_TYPE_VIOLATION);
        } catch (IntrospectionException | MBeanException | ReflectionException
                 | RuntimeMBeanException | RuntimeErrorException invocationFailed) {
            return new ProbeResult.Indeterminate(ProbeReason.INVOCATION_FAILED, invocationFailed);
        } catch (SecurityException accessDenied) {
            return new ProbeResult.Indeterminate(ProbeReason.ACCESS_DENIED, accessDenied);
        }

        if (!(raw instanceof CompositeData composite)
                || !JmxOpenTypes.READINESS_SNAPSHOT.equals(composite.getCompositeType())) {
            return new ProbeResult.Rejected(ProbeReason.MALFORMED_SNAPSHOT);
        }

        try {
            return new ProbeResult.Present(decode(composite));
        } catch (IllegalArgumentException | IllegalStateException malformedSnapshot) {
            return new ProbeResult.Rejected(ProbeReason.MALFORMED_SNAPSHOT);
        }
    }

    private static AgentReadinessSnapshot decode(CompositeData snapshot) {
        String contractId = field(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_CONTRACT_ID, String.class);
        String schemaDigest = field(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_SCHEMA_DIGEST, String.class);
        String runtimeEpoch = field(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_RUNTIME_EPOCH, String.class);
        long evidenceGeneration = field(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_EVIDENCE_GENERATION, Long.class);
        String batchId = field(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_BATCH_ID, String.class);
        String lifecycle = token(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE);
        Set<String> capabilities = stringSet(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_CAPABILITIES);
        Set<String> missingRequired = stringSet(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_MISSING_REQUIRED);
        String failureCode = optionalToken(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE);

        String agentDistributionSha256 = field(
                snapshot,
                JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_DISTRIBUTION_SHA256,
                String.class
        );

        String upstreamAgentVersion = field(
                snapshot,
                JmxWireContract.FIELD_READINESS_SNAPSHOT_UPSTREAM_AGENT_VERSION,
                String.class
        );

        String extensionVersion = field(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_EXTENSION_VERSION, String.class);

        String extensionDefiningLoader = field(
                snapshot,
                JmxWireContract.FIELD_READINESS_SNAPSHOT_EXTENSION_DEFINING_LOADER,
                String.class
        );

        String sdkDefiningLoader = field(
                snapshot,
                JmxWireContract.FIELD_READINESS_SNAPSHOT_SDK_DEFINING_LOADER,
                String.class
        );

        return new AgentReadinessSnapshot(
                new AgentReadinessSnapshot.ContractIdentity(contractId, schemaDigest),
                new AgentReadinessSnapshot.AnchorIdentity(runtimeEpoch, evidenceGeneration, batchId),
                new AgentReadinessSnapshot.LifecycleState(lifecycle, failureCode, capabilities, missingRequired),
                new AgentReadinessSnapshot.DistributionIdentity(agentDistributionSha256, upstreamAgentVersion, extensionVersion),
                new AgentReadinessSnapshot.ClassloaderTopology(extensionDefiningLoader, sdkDefiningLoader)
        );
    }

    private static Set<String> stringSet(CompositeData snapshot, String fieldName) {
        String[] values = field(snapshot, fieldName, String[].class);
        if (values.length > JmxWireContract.MAX_CAPABILITY_ENTRIES) {
            throw new IllegalArgumentException("Readiness string set exceeds the generated entry limit");
        }

        Set<String> result = new LinkedHashSet<>(values.length);
        for (String value : values) {
            String token = requireToken(value, fieldName);
            if (!result.add(token)) {
                throw new IllegalArgumentException("Readiness string set contains a duplicate token");
            }
        }
        return Set.copyOf(result);
    }

    @SuppressWarnings("SameParameterValue")
    private static String token(CompositeData snapshot, String fieldName) {
        return requireToken(field(snapshot, fieldName, String.class), fieldName);
    }

    @SuppressWarnings("SameParameterValue")
    private static String optionalToken(CompositeData snapshot, String fieldName) {
        String value = field(snapshot, fieldName, String.class);
        return value.isEmpty() ? value : requireToken(value, fieldName);
    }

    private static String requireToken(String value, String fieldName) {
        if (value == null || value.isBlank() || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Readiness field is not a canonical token: " + fieldName);
        }
        return value;
    }

    private static <T> T field(CompositeData snapshot, String fieldName, Class<T> expectedType) {
        Object value = snapshot.get(fieldName);
        if (!expectedType.isInstance(value)) {
            throw new IllegalArgumentException("Readiness field has an invalid type: " + fieldName);
        }
        return expectedType.cast(value);
    }

    enum ProbeReason {
        ENDPOINT_DISAPPEARED("READINESS_ENDPOINT_DISAPPEARED"),
        ACCESS_DENIED("READINESS_ACCESS_DENIED"),
        INVOCATION_FAILED("READINESS_INVOCATION_FAILED"),
        INCOMPATIBLE_METADATA("READINESS_MBEAN_INFO_SHAPE_VIOLATION"),
        JMX_WIRE_TYPE_VIOLATION("JMX_WIRE_TYPE_VIOLATION"),
        MALFORMED_SNAPSHOT("MALFORMED_READINESS_SNAPSHOT"),
        MIXED_GENERATION("READINESS_MIXED_GENERATION");

        private final String failureCode;

        ProbeReason(String failureCode) {
            this.failureCode = failureCode;
        }

        String failureCode() {
            return failureCode;
        }
    }

    sealed interface ProbeResult permits ProbeResult.Present, ProbeResult.Rejected, ProbeResult.Indeterminate {

        static ProbeReason reasonOf(ProbeResult result) {
            return switch (result) {
                case Rejected rejected -> rejected.reason();
                case Indeterminate indeterminate -> indeterminate.reason();
                case Present ignored -> throw new IllegalStateException("Present readiness does not have a probe failure reason");
            };
        }

        record Present(AgentReadinessSnapshot snapshot) implements ProbeResult {
            public Present {
                Objects.requireNonNull(snapshot, "snapshot");
            }
        }

        record Rejected(ProbeReason reason) implements ProbeResult {
            public Rejected {
                Objects.requireNonNull(reason, "reason");
            }
        }

        record Indeterminate(ProbeReason reason, Throwable cause) implements ProbeResult {
            public Indeterminate {
                Objects.requireNonNull(reason, "reason");
                Objects.requireNonNull(cause, "cause");
            }
        }
    }
}
