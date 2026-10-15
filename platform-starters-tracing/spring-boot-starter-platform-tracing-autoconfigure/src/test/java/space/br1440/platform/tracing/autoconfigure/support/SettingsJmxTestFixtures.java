package space.br1440.platform.tracing.autoconfigure.support;

import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanOperationInfo;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenMBeanAttributeInfo;
import javax.management.openmbean.OpenMBeanAttributeInfoSupport;
import javax.management.openmbean.OpenMBeanConstructorInfo;
import javax.management.openmbean.OpenMBeanInfoSupport;
import javax.management.openmbean.OpenMBeanOperationInfo;
import javax.management.openmbean.OpenMBeanOperationInfoSupport;
import javax.management.openmbean.OpenMBeanParameterInfo;
import javax.management.openmbean.OpenMBeanParameterInfoSupport;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

public final class SettingsJmxTestFixtures {

    private SettingsJmxTestFixtures() {
    }

    public static MBeanInfo statusInfo() {
        return statusInfo(false);
    }

    public static MBeanInfo statusInfo(boolean writable) {
        OpenMBeanAttributeInfo snapshot = new OpenMBeanAttributeInfoSupport(
                JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT,
                "snapshot",
                JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT,
                true,
                writable,
                false
        );

        return new OpenMBeanInfoSupport(
                "status",
                "status",
                new OpenMBeanAttributeInfo[]{snapshot},
                new OpenMBeanConstructorInfo[0],
                new OpenMBeanOperationInfo[0],
                new MBeanNotificationInfo[0]
        );
    }

    public static MBeanInfo mutationInfo() {
        return mutationInfo(JmxOpenTypes.APPLY_SNAPSHOT_REQUEST);
    }

    public static MBeanInfo legacyMutationInfo() throws OpenDataException {
        CompositeType legacyRequest = new CompositeType(
                JmxOpenTypes.APPLY_SNAPSHOT_REQUEST.getTypeName(),
                "legacy request",
                new String[]{
                        "attemptId",
                        "expectedAcceptedSequence",
                        "expectedAgentInstanceId",
                        "runtimePolicy",
                        "sourceAuthority",
                        "applyTrigger",
                        "sourceRevision",
                        "updateTarget"
                },
                new String[]{
                        "attemptId",
                        "expectedAcceptedSequence",
                        "expectedAgentInstanceId",
                        "runtimePolicy",
                        "sourceAuthority",
                        "applyTrigger",
                        "sourceRevision",
                        "updateTarget"
                },
                new OpenType<?>[]{
                        SimpleType.STRING,
                        SimpleType.LONG,
                        SimpleType.STRING,
                        JmxOpenTypes.RUNTIME_POLICY,
                        SimpleType.STRING,
                        SimpleType.STRING,
                        SimpleType.STRING,
                        SimpleType.STRING
                }
        );
        return mutationInfo(legacyRequest);
    }

    private static MBeanInfo mutationInfo(CompositeType requestType) {
        OpenMBeanParameterInfo request = new OpenMBeanParameterInfoSupport(
                JmxWireContract.PARAMETER_SETTINGS_MUTATION_APPLY_SNAPSHOT_REQUEST,
                "request",
                requestType
        );

        OpenMBeanOperationInfo operation = new OpenMBeanOperationInfoSupport(
                JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT,
                "apply",
                new OpenMBeanParameterInfo[]{request},
                JmxOpenTypes.APPLY_SNAPSHOT_RESPONSE,
                MBeanOperationInfo.ACTION
        );

        return new OpenMBeanInfoSupport(
                "mutation",
                "mutation",
                new OpenMBeanAttributeInfo[0],
                new OpenMBeanConstructorInfo[0],
                new OpenMBeanOperationInfo[]{operation},
                new MBeanNotificationInfo[0]
        );
    }
}
