package space.br1440.platform.tracing.otel.javaagent.jmx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.management.Descriptor;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanFeatureInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import lombok.experimental.UtilityClass;

@UtilityClass
final class JmxManagementContractSnapshot {

    /** P6.5/P6.6: platform domain MBeans are read-only; mutations use private control plane. */
    private static final Set<String> MUTATING_OPERATIONS = Set.of();

    static Snapshot capture(MBeanServer server, List<ObjectName> objectNames) throws Exception {
        List<ObjectName> sortedNames = objectNames.stream()
                .sorted(Comparator.comparing(ObjectName::getCanonicalName))
                .toList();
        List<MBeanContract> contracts = new ArrayList<>();
        Set<String> observedMutations = new LinkedHashSet<>();
        int memberCount = 0;
        int mutatingMemberCount = 0;

        for (ObjectName objectName : sortedNames) {
            if (!server.isRegistered(objectName)) {
                throw new AssertionError("MBean is not registered: " + objectName.getCanonicalName());
            }
            MBeanInfo info = server.getMBeanInfo(objectName);
            List<AttributeContract> attributes = Arrays.stream(info.getAttributes())
                    .sorted(Comparator.comparing(MBeanAttributeInfo::getName))
                    .map(attribute -> new AttributeContract(
                            attribute.getName(),
                            attribute.getDescription(),
                            attribute.getType(),
                            descriptor(attribute),
                            attribute.isReadable(),
                            attribute.isWritable(),
                            attribute.isIs()))
                    .toList();
            List<OperationContract> operations = Arrays.stream(info.getOperations())
                    .map(operation -> operationContract(objectName, operation, observedMutations))
                    .sorted(Comparator.comparing(OperationContract::sortKey))
                    .toList();
            List<NotificationContract> notifications = Arrays.stream(info.getNotifications())
                    .map(JmxManagementContractSnapshot::notificationContract)
                    .sorted(Comparator.comparing(NotificationContract::sortKey))
                    .toList();

            memberCount += attributes.size() + operations.size();
            mutatingMemberCount += (int) attributes.stream().filter(AttributeContract::writable).count();
            mutatingMemberCount += (int) operations.stream().filter(OperationContract::mutating).count();
            contracts.add(new MBeanContract(
                    objectName.getCanonicalName(),
                    info.getClassName(),
                    info.getDescription(),
                    descriptor(info),
                    attributes,
                    operations,
                    notifications));
        }

        if (!observedMutations.equals(MUTATING_OPERATIONS)) {
            Set<String> missing = new LinkedHashSet<>(MUTATING_OPERATIONS);
            missing.removeAll(observedMutations);
            Set<String> unexpected = new LinkedHashSet<>(observedMutations);
            unexpected.removeAll(MUTATING_OPERATIONS);
            throw new AssertionError("Mutating operations classification is stale. missing="
                    + missing + ", unexpected=" + unexpected);
        }

        return new Snapshot(
                contracts.size(),
                memberCount,
                memberCount - mutatingMemberCount,
                mutatingMemberCount,
                contracts);
    }

    static String toJson(Snapshot snapshot) {
        Json json = new Json();
        json.line("{");
        json.property(1, "schemaVersion", "1", true);
        json.property(1, "objectNameCount", Integer.toString(snapshot.objectNameCount()), true);
        json.property(1, "memberCount", Integer.toString(snapshot.memberCount()), true);
        json.property(1, "readOnlyMemberCount", Integer.toString(snapshot.readOnlyMemberCount()), true);
        json.property(1, "mutatingMemberCount", Integer.toString(snapshot.mutatingMemberCount()), true);
        json.line(1, "\"objectNames\": [");
        for (int index = 0; index < snapshot.objectNames().size(); index++) {
            writeMBean(json, snapshot.objectNames().get(index), index + 1 < snapshot.objectNames().size());
        }
        json.line(1, "]");
        json.line("}");
        return json.toString();
    }

    private static OperationContract operationContract(
            ObjectName objectName,
            MBeanOperationInfo operation,
            Set<String> observedMutations) {
        List<ParameterContract> parameters = Arrays.stream(operation.getSignature())
                .map(parameter -> new ParameterContract(
                        parameter.getName(),
                        parameter.getDescription(),
                        parameter.getType(),
                        descriptor(parameter)))
                .toList();
        String key = operation(
                objectName,
                operation.getName(),
                parameters.stream().map(ParameterContract::type).toArray(String[]::new));
        boolean mutating = MUTATING_OPERATIONS.contains(key);
        if (mutating) {
            observedMutations.add(key);
        }
        return new OperationContract(
                operation.getName(),
                operation.getDescription(),
                parameters,
                operation.getReturnType(),
                descriptor(operation),
                operation.getImpact(),
                mutating);
    }

    private static NotificationContract notificationContract(MBeanNotificationInfo notification) {
        return new NotificationContract(
                notification.getName(),
                notification.getDescription(),
                Arrays.stream(notification.getNotifTypes()).sorted().toList(),
                descriptor(notification));
    }

    private static List<DescriptorField> descriptor(MBeanFeatureInfo info) {
        return descriptor(info.getDescriptor());
    }

    private static List<DescriptorField> descriptor(MBeanInfo info) {
        return descriptor(info.getDescriptor());
    }

    private static List<DescriptorField> descriptor(Descriptor descriptor) {
        return Arrays.stream(descriptor.getFieldNames())
                .sorted()
                .map(name -> new DescriptorField(name, descriptorValue(descriptor.getFieldValue(name))))
                .toList();
    }

    private static String descriptorValue(Object value) {
        return switch (value) {
            case null -> "null";
            case Object[] array -> Arrays.deepToString(array);
            case int[] array -> Arrays.toString(array);
            case long[] array -> Arrays.toString(array);
            case boolean[] array -> Arrays.toString(array);
            case byte[] array -> Arrays.toString(array);
            case short[] array -> Arrays.toString(array);
            case char[] array -> Arrays.toString(array);
            case float[] array -> Arrays.toString(array);
            case double[] array -> Arrays.toString(array);
            default -> value.toString();
        };
    }

    private static String operation(ObjectName objectName, String name, String... parameterTypes) {
        return objectName.getCanonicalName() + "#" + name + "(" + String.join(",", parameterTypes) + ")";
    }

    private static void writeMBean(Json json, MBeanContract mbean, boolean comma) {
        json.line(2, "{");
        json.property(3, "objectName", quote(mbean.objectName()), true);
        json.property(3, "className", quote(mbean.className()), true);
        json.property(3, "description", quote(mbean.description()), true);
        writeDescriptor(json, 3, mbean.descriptor(), true);
        json.line(3, "\"attributes\": [");
        for (int index = 0; index < mbean.attributes().size(); index++) {
            writeAttribute(json, mbean.attributes().get(index), index + 1 < mbean.attributes().size());
        }
        json.line(3, "],");
        json.line(3, "\"operations\": [");
        for (int index = 0; index < mbean.operations().size(); index++) {
            writeOperation(json, mbean.operations().get(index), index + 1 < mbean.operations().size());
        }
        json.line(3, "],");
        json.line(3, "\"notifications\": [");
        for (int index = 0; index < mbean.notifications().size(); index++) {
            writeNotification(json, mbean.notifications().get(index),
                    index + 1 < mbean.notifications().size());
        }
        json.line(3, "]");
        json.line(2, comma ? "}," : "}");
    }

    private static void writeAttribute(Json json, AttributeContract attribute, boolean comma) {
        json.line(4, "{");
        json.property(5, "name", quote(attribute.name()), true);
        json.property(5, "description", quote(attribute.description()), true);
        json.property(5, "javaType", quote(attribute.type()), true);
        writeDescriptor(json, 5, attribute.descriptor(), true);
        json.property(5, "readable", Boolean.toString(attribute.readable()), true);
        json.property(5, "writable", Boolean.toString(attribute.writable()), true);
        json.property(5, "isIs", Boolean.toString(attribute.isIs()), false);
        json.line(4, comma ? "}," : "}");
    }

    private static void writeOperation(Json json, OperationContract operation, boolean comma) {
        json.line(4, "{");
        json.property(5, "name", quote(operation.name()), true);
        json.property(5, "description", quote(operation.description()), true);
        json.line(5, "\"arguments\": [");
        for (int index = 0; index < operation.parameters().size(); index++) {
            ParameterContract parameter = operation.parameters().get(index);
            json.line(6, "{");
            json.property(7, "name", quote(parameter.name()), true);
            json.property(7, "description", quote(parameter.description()), true);
            json.property(7, "javaType", quote(parameter.type()), true);
            writeDescriptor(json, 7, parameter.descriptor(), false);
            json.line(6, index + 1 < operation.parameters().size() ? "}," : "}");
        }
        json.line(5, "],");
        json.property(5, "returnJavaType", quote(operation.returnType()), true);
        writeDescriptor(json, 5, operation.descriptor(), true);
        json.property(5, "impact", Integer.toString(operation.impact()), true);
        json.property(5, "mutating", Boolean.toString(operation.mutating()), false);
        json.line(4, comma ? "}," : "}");
    }

    private static void writeNotification(Json json, NotificationContract notification, boolean comma) {
        json.line(4, "{");
        json.property(5, "name", quote(notification.name()), true);
        json.property(5, "description", quote(notification.description()), true);
        json.line(5, "\"notificationTypes\": [");
        for (int index = 0; index < notification.types().size(); index++) {
            json.line(6, quote(notification.types().get(index))
                    + (index + 1 < notification.types().size() ? "," : ""));
        }
        json.line(5, "],");
        writeDescriptor(json, 5, notification.descriptor(), false);
        json.line(4, comma ? "}," : "}");
    }

    private static void writeDescriptor(
            Json json,
            int indent,
            List<DescriptorField> fields,
            boolean comma) {
        json.line(indent, "\"descriptor\": [");
        for (int index = 0; index < fields.size(); index++) {
            DescriptorField field = fields.get(index);
            json.line(indent + 1, "{");
            json.property(indent + 2, "name", quote(field.name()), true);
            json.property(indent + 2, "value", quote(field.value()), false);
            json.line(indent + 1, index + 1 < fields.size() ? "}," : "}");
        }
        json.line(indent, comma ? "]," : "]");
    }

    private static String quote(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t") + "\"";
    }

    record Snapshot(
            int objectNameCount,
            int memberCount,
            int readOnlyMemberCount,
            int mutatingMemberCount,
            List<MBeanContract> objectNames) {
    }

    record MBeanContract(
            String objectName,
            String className,
            String description,
            List<DescriptorField> descriptor,
            List<AttributeContract> attributes,
            List<OperationContract> operations,
            List<NotificationContract> notifications) {
    }

    record AttributeContract(
            String name,
            String description,
            String type,
            List<DescriptorField> descriptor,
            boolean readable,
            boolean writable,
            boolean isIs) {
    }

    record OperationContract(
            String name,
            String description,
            List<ParameterContract> parameters,
            String returnType,
            List<DescriptorField> descriptor,
            int impact,
            boolean mutating) {

        String sortKey() {
            return name + "(" + String.join(",", parameters.stream()
                    .map(ParameterContract::type)
                    .toList()) + ")";
        }
    }

    record ParameterContract(
            String name,
            String description,
            String type,
            List<DescriptorField> descriptor) {
    }

    record NotificationContract(
            String name,
            String description,
            List<String> types,
            List<DescriptorField> descriptor) {

        String sortKey() {
            return name + ":" + String.join(",", types);
        }
    }

    record DescriptorField(String name, String value) {
    }

    private static final class Json {

        private static final String INDENT = "  ";
        private final StringBuilder value = new StringBuilder();

        void line(String text) {
            value.append(text).append('\n');
        }

        void line(int indent, String text) {
            value.append(INDENT.repeat(indent)).append(text).append('\n');
        }

        void property(int indent, String name, String propertyValue, boolean comma) {
            line(indent, quote(name) + ": " + propertyValue + (comma ? "," : ""));
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }
}
