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
        try {
            Object raw = readAttributeOfVerifiedShape(readinessName);
            if (hasNullPrimaryFailureCode(raw)) {
                return new ProbeResult.Rejected(ProbeReason.MALFORMED_SNAPSHOT);
            }

            wireValidator.validate(raw);
            return decode(raw);
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
    }

    private Object readAttributeOfVerifiedShape(ObjectName readinessName)
            throws InstanceNotFoundException, IntrospectionException, ReflectionException,
                   AttributeNotFoundException, MBeanException {
        MBeanInfo metadata = server.getMBeanInfo(readinessName);
        JmxMBeanInfoShape.requireReadOnlySnapshot(metadata, READINESS_ATTRIBUTE, JmxOpenTypes.READINESS_SNAPSHOT);
        return server.getAttribute(readinessName, READINESS_ATTRIBUTE);
    }

    private static boolean hasNullPrimaryFailureCode(Object raw) {
        return raw instanceof CompositeData composite
                && (raw.getClass().getClassLoader() == null)
                && JmxOpenTypes.READINESS_SNAPSHOT.equals(composite.getCompositeType())
                && (composite.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_PRIMARY_FAILURE_CODE) == null);
    }

    private static ProbeResult decode(Object raw) {
        if (!(raw instanceof CompositeData composite)
                || !JmxOpenTypes.READINESS_SNAPSHOT.equals(composite.getCompositeType())) {
            return new ProbeResult.Rejected(ProbeReason.MALFORMED_SNAPSHOT);
        }

        ReadinessFields fields = new ReadinessFields(composite);
        try {
            return new ProbeResult.Present(new AgentReadinessSnapshot(
                    contractIdentity(fields),
                    anchorIdentity(fields),
                    lifecycleState(fields),
                    distributionIdentity(fields),
                    classloaderTopology(fields)
            ));
        } catch (IllegalArgumentException | IllegalStateException malformedSnapshot) {
            return new ProbeResult.Rejected(ProbeReason.MALFORMED_SNAPSHOT);
        }
    }

    private static AgentReadinessSnapshot.ContractIdentity contractIdentity(ReadinessFields fields) {
        return new AgentReadinessSnapshot.ContractIdentity(
                fields.string(JmxWireContract.FIELD_READINESS_SNAPSHOT_CONTRACT_ID),
                fields.string(JmxWireContract.FIELD_READINESS_SNAPSHOT_SCHEMA_DIGEST)
        );
    }

    private static AgentReadinessSnapshot.AnchorIdentity anchorIdentity(ReadinessFields fields) {
        String agentInstanceId = AgentInstanceIdValidator.requireCanonical(
                fields.string(JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_INSTANCE_ID),
                JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_INSTANCE_ID
        );

        return new AgentReadinessSnapshot.AnchorIdentity(
                agentInstanceId,
                fields.evidenceGeneration(),
                fields.string(JmxWireContract.FIELD_READINESS_SNAPSHOT_BATCH_ID)
        );
    }

    private static AgentReadinessSnapshot.LifecycleState lifecycleState(ReadinessFields fields) {
        return new AgentReadinessSnapshot.LifecycleState(
                fields.lifecycle(),
                fields.optionalToken(JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE),
                fields.optionalToken(JmxWireContract.FIELD_READINESS_SNAPSHOT_PRIMARY_FAILURE_CODE),
                fields.tokenSet(JmxWireContract.FIELD_READINESS_SNAPSHOT_CAPABILITIES),
                fields.tokenSet(JmxWireContract.FIELD_READINESS_SNAPSHOT_MISSING_REQUIRED)
        );
    }

    private static AgentReadinessSnapshot.DistributionIdentity distributionIdentity(ReadinessFields fields) {
        return new AgentReadinessSnapshot.DistributionIdentity(
                fields.string(JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_DISTRIBUTION_SHA256),
                fields.string(JmxWireContract.FIELD_READINESS_SNAPSHOT_UPSTREAM_AGENT_VERSION),
                fields.string(JmxWireContract.FIELD_READINESS_SNAPSHOT_EXTENSION_VERSION)
        );
    }

    private static AgentReadinessSnapshot.ClassloaderTopology classloaderTopology(ReadinessFields fields) {
        return new AgentReadinessSnapshot.ClassloaderTopology(
                fields.string(JmxWireContract.FIELD_READINESS_SNAPSHOT_EXTENSION_DEFINING_LOADER),
                fields.string(JmxWireContract.FIELD_READINESS_SNAPSHOT_SDK_DEFINING_LOADER)
        );
    }

    private record ReadinessFields(CompositeData data) {

        String string(String fieldName) {
            return field(fieldName, String.class);
        }

        long evidenceGeneration() {
            return field(JmxWireContract.FIELD_READINESS_SNAPSHOT_EVIDENCE_GENERATION, Long.class);
        }

        String lifecycle() {
            return requireToken(
                    string(JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE),
                    JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE
            );
        }

        String optionalToken(String fieldName) {
            String value = string(fieldName);
            return value.isEmpty() ? value : requireToken(value, fieldName);
        }

        Set<String> tokenSet(String fieldName) {
            String[] values = field(fieldName, String[].class);
            if (values.length > JmxWireContract.MAX_CAPABILITY_ENTRIES) {
                throw new IllegalArgumentException("Readiness string set exceeds the generated entry limit: " + fieldName);
            }

            Set<String> tokens = new LinkedHashSet<>(values.length);
            for (String value : values) {
                if (!tokens.add(requireToken(value, fieldName))) {
                    throw new IllegalArgumentException("Readiness string set contains a duplicate token: " + fieldName);
                }
            }
            return Set.copyOf(tokens);
        }

        private <T> T field(String fieldName, Class<T> expectedType) {
            Object value = data.get(fieldName);
            if (!expectedType.isInstance(value)) {
                throw new IllegalArgumentException("Readiness field has an invalid type: " + fieldName);
            }
            return expectedType.cast(value);
        }

        private static String requireToken(String value, String fieldName) {
            if (value == null || value.isBlank() || !value.equals(value.trim())
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Readiness field is not a canonical token: " + fieldName);
            }
            return value;
        }
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
