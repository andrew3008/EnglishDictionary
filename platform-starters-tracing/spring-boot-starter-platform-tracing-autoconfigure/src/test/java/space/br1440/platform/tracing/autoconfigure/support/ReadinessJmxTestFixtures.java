package space.br1440.platform.tracing.autoconfigure.support;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.OpenMBeanAttributeInfo;
import javax.management.openmbean.OpenMBeanAttributeInfoSupport;
import javax.management.openmbean.OpenMBeanConstructorInfo;
import javax.management.openmbean.OpenMBeanInfoSupport;
import javax.management.openmbean.OpenMBeanOperationInfo;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

public final class ReadinessJmxTestFixtures {

    public static final String AGENT_INSTANCE_ID = "fe2c56ee-8f3d-4f43-b640-4bfc973d788c";
    public static final long EVIDENCE_GENERATION = 1L;
    public static final String BATCH_ID = "test-batch";

    private static final String SHA256 = "a".repeat(64);
    private static final List<String> PRE_RUNTIME_BINDING_CAPABILITIES = List.of(
            "CONFIGURATION_LOADED",
            "REQUIRED_SPAN_PROCESSORS_INSTALLED",
            "PROPAGATION_HOOKS_INSTALLED",
            "SAFE_EXPORTER_INSTALLED",
            "EXPORT_PATH_PROTECTED"
    );
    private static final List<String> PRE_RUNTIME_BINDING_MISSING_REQUIRED = List.of(
            "PLATFORM_SAMPLER_INSTALLED",
            "SANITIZER_INSTALLED"
    );

    private ReadinessJmxTestFixtures() {
    }

    public static CompositeData readySnapshot() {
        return readySnapshot(AGENT_INSTANCE_ID, EVIDENCE_GENERATION, BATCH_ID);
    }

    public static CompositeData readySnapshot(String agentInstanceId,
                                              long evidenceGeneration,
                                              String batchId) {
        return readySnapshot(agentInstanceId, evidenceGeneration, batchId, "1.2.3");
    }

    public static CompositeData readySnapshotForExtensionVersion(String extensionVersion) {
        return readySnapshot(AGENT_INSTANCE_ID, EVIDENCE_GENERATION, BATCH_ID, extensionVersion);
    }

    private static CompositeData readySnapshot(String agentInstanceId,
                                               long evidenceGeneration,
                                               String batchId,
                                               String extensionVersion) {
        return composite(
                JmxWireContract.SCHEMA_ID,
                JmxWireContract.SCHEMA_DIGEST_SHA256,
                agentInstanceId,
                evidenceGeneration,
                batchId,
                "READY",
                JmxWireContract.REQUIRED_CAPABILITIES,
                List.of(),
                "",
                "",
                SHA256,
                "2.30.0",
                extensionVersion,
                "agent-loader@7e57",
                "sdk-loader@7e58");
    }

    public static CompositeData failedSnapshot(String failureCode) {
        return failedSnapshot(failureCode, failureCode);
    }

    public static CompositeData failedSnapshot(String primaryFailureCode,
                                               String effectiveFailureCode) {
        return composite(
                JmxWireContract.SCHEMA_ID,
                JmxWireContract.SCHEMA_DIGEST_SHA256,
                AGENT_INSTANCE_ID,
                0L,
                "",
                "FAILED",
                PRE_RUNTIME_BINDING_CAPABILITIES,
                PRE_RUNTIME_BINDING_MISSING_REQUIRED,
                effectiveFailureCode,
                primaryFailureCode,
                SHA256,
                "2.30.0",
                "1.2.3",
                "agent-loader@7e57",
                "sdk-loader@7e58");
    }

    public static CompositeData composite(String contractId,
                                          String schemaDigest,
                                          String agentInstanceId,
                                          long evidenceGeneration,
                                          String batchId,
                                          String lifecycle,
                                          Collection<String> capabilities,
                                          Collection<String> missingRequired,
                                          String failureCode,
                                          String primaryFailureCode,
                                          String agentDistributionSha256,
                                          String upstreamAgentVersion,
                                          String extensionVersion,
                                          String extensionDefiningLoader,
                                          String sdkDefiningLoader) {
        try {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_CONTRACT_ID, contractId);
            fields.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_SCHEMA_DIGEST, schemaDigest);
            fields.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_INSTANCE_ID, agentInstanceId);
            fields.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_EVIDENCE_GENERATION, evidenceGeneration);
            fields.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_BATCH_ID, batchId);
            fields.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE, lifecycle);
            fields.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_CAPABILITIES, capabilities.toArray(String[]::new));
            fields.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_MISSING_REQUIRED, missingRequired.toArray(String[]::new));
            fields.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE, failureCode);
            fields.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_PRIMARY_FAILURE_CODE, primaryFailureCode);
            fields.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_DISTRIBUTION_SHA256, agentDistributionSha256);
            fields.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_UPSTREAM_AGENT_VERSION, upstreamAgentVersion);
            fields.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_EXTENSION_VERSION, extensionVersion);
            fields.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_EXTENSION_DEFINING_LOADER, extensionDefiningLoader);
            fields.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_SDK_DEFINING_LOADER, sdkDefiningLoader);
            return new CompositeDataSupport(JmxOpenTypes.READINESS_SNAPSHOT, fields);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create readiness test snapshot", exception);
        }
    }

    public static MBeanInfo metadata() {
        OpenMBeanAttributeInfo snapshot = new OpenMBeanAttributeInfoSupport(
                JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT,
                "Immutable readiness snapshot",
                JmxOpenTypes.READINESS_SNAPSHOT,
                true,
                false,
                false);

        return new OpenMBeanInfoSupport(
                ReadinessJmxTestFixtures.class.getName(),
                "Readiness test MBean",
                new OpenMBeanAttributeInfo[]{snapshot},
                new OpenMBeanConstructorInfo[0],
                new OpenMBeanOperationInfo[0],
                new MBeanNotificationInfo[0]
        );
    }

    public static DynamicMBean endpoint(Supplier<Object> snapshotSupplier) {
        return new DynamicMBean() {
            @Override
            public Object getAttribute(String attribute) throws AttributeNotFoundException {
                if (!JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT
                        .equals(attribute)) {
                    throw new AttributeNotFoundException("Unknown readiness attribute: " + attribute);
                }

                return snapshotSupplier.get();
            }

            @Override
            public void setAttribute(Attribute attribute) throws MBeanException {
                throw new MBeanException(new Exception("READINESS_READ_ONLY"), "Readiness is read-only");
            }

            @Override
            public AttributeList getAttributes(String[] attributes) {
                return new AttributeList();
            }

            @Override
            public AttributeList setAttributes(AttributeList attributes) {
                return new AttributeList();
            }

            @Override
            public Object invoke(String actionName, Object[] params, String[] signature) throws ReflectionException {
                throw new ReflectionException(
                        new NoSuchMethodException(actionName),
                        "Readiness endpoint exposes no operations"
                );
            }

            @Override
            public MBeanInfo getMBeanInfo() {
                return metadata();
            }
        };
    }
}
