package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.openmbean.OpenMBeanAttributeInfo;
import javax.management.openmbean.OpenMBeanInfo;
import javax.management.openmbean.OpenMBeanOperationInfo;
import javax.management.openmbean.OpenMBeanParameterInfo;
import javax.management.openmbean.OpenType;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class JmxMBeanInfoShape {

    public static void requireReadOnlySnapshot(MBeanInfo info,
                                               String attributeName,
                                               OpenType<?> snapshotOpenType) {
        if (!(info instanceof OpenMBeanInfo)) {
            throw new JmxMBeanInfoShapeViolationException();
        }

        try {
            requireEmpty(info.getConstructors());
            requireEmpty(info.getNotifications());
            requireEmpty(info.getOperations());

            MBeanAttributeInfo[] attributes = info.getAttributes();
            if (attributes == null
                    || attributes.length != 1
                    || !(attributes[0] instanceof OpenMBeanAttributeInfo openAttribute)
                    || !attributeName.equals(attributes[0].getName())
                    || !snapshotOpenType.equals(openAttribute.getOpenType())
                    || !attributes[0].isReadable()
                    || attributes[0].isWritable()
                    || attributes[0].isIs()) {
                throw new JmxMBeanInfoShapeViolationException();
            }
        } catch (JmxMBeanInfoShapeViolationException violation) {
            throw violation;
        } catch (RuntimeException malformedMetadata) {
            throw new JmxMBeanInfoShapeViolationException();
        }
    }

    public static void requireMutation(MBeanInfo info,
                                       String operationName,
                                       String parameterName,
                                       OpenType<?> requestOpenType,
                                       OpenType<?> responseOpenType) {
        if (!(info instanceof OpenMBeanInfo)) {
            throw new JmxMBeanInfoShapeViolationException();
        }

        try {
            requireEmpty(info.getConstructors());
            requireEmpty(info.getNotifications());
            requireEmpty(info.getAttributes());

            MBeanOperationInfo[] operations = info.getOperations();
            if (operations == null
                    || operations.length != 1
                    || !(operations[0] instanceof OpenMBeanOperationInfo openOperation)
                    || !operationName.equals(operations[0].getName())
                    || !responseOpenType.equals(openOperation.getReturnOpenType())
                    || operations[0].getImpact() != MBeanOperationInfo.ACTION) {
                throw new JmxMBeanInfoShapeViolationException();
            }

            MBeanParameterInfo[] signature = operations[0].getSignature();
            if (signature == null
                    || signature.length != 1
                    || !(signature[0] instanceof OpenMBeanParameterInfo openParameter)
                    || openParameter.getOpenType() == null
                    || signature[0].getName() == null
                    || signature[0].getName().isBlank()
                    || !parameterName.equals(signature[0].getName())
                    || !requestOpenType.equals(openParameter.getOpenType())) {
                throw new JmxMBeanInfoShapeViolationException();
            }
        } catch (JmxMBeanInfoShapeViolationException violation) {
            throw violation;
        } catch (RuntimeException malformedMetadata) {
            throw new JmxMBeanInfoShapeViolationException();
        }
    }

    private static void requireEmpty(Object[] declared) {
        if (declared == null || declared.length != 0) {
            throw new JmxMBeanInfoShapeViolationException();
        }
    }
}
