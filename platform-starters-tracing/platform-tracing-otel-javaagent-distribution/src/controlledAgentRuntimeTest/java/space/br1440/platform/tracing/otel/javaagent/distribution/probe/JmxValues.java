package space.br1440.platform.tracing.otel.javaagent.distribution.probe;

import java.util.Set;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.TabularData;

final class JmxValues {

    private JmxValues() {
    }

    static CompositeData attribute(MBeanServer server, ObjectName name, String attribute, CompositeType expected) {
        Object raw;
        try {
            raw = server.getAttribute(name, attribute);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read JMX attribute %s of %s".formatted(attribute, name), failure);
        }

        CompositeData data = asComposite(raw, attribute);
        requireOpenType(data, expected, attribute);
        return data;
    }

    static void requireOpenType(CompositeData value, CompositeType expected, String label) {
        if (!expected.equals(value.getCompositeType())) {
            throw new IllegalStateException("%s OpenType differs from the generated contract: actual=%s"
                    .formatted(label, value.getCompositeType().getTypeName()));
        }
    }

    @SuppressWarnings("SameParameterValue")
    static CompositeData composite(CompositeData data, String field) {
        return asComposite(data.get(field), field);
    }

    static CompositeData asComposite(Object value, String label) {
        return cast(value, CompositeData.class, label);
    }

    static TabularData tabular(CompositeData data, String field) {
        return cast(data.get(field), TabularData.class, field);
    }

    static boolean bool(CompositeData data, String field) {
        return cast(data.get(field), Boolean.class, field);
    }

    static long longValue(CompositeData data, String field) {
        return cast(data.get(field), Long.class, field);
    }

    static double doubleValue(CompositeData data, String field) {
        return cast(data.get(field), Double.class, field);
    }

    static String string(CompositeData data, String field) {
        return cast(data.get(field), String.class, field);
    }

    static String[] strings(CompositeData data, String field) {
        return cast(data.get(field), String[].class, field);
    }

    static String nonBlankString(CompositeData data, String field) {
        String text = string(data, field);
        if (text.isBlank()) {
            throw new IllegalStateException("JMX string field must not be blank: " + field);
        }
        return text;
    }

    @SuppressWarnings("SameParameterValue")
    static String token(CompositeData data, String field, Set<String> vocabulary) {
        String value = nonBlankString(data, field);
        if (!vocabulary.contains(value)) {
            throw new IllegalStateException("Unknown %s token: %s".formatted(field, value));
        }
        return value;
    }

    private static <T> T cast(Object value, Class<T> type, String label) {
        if (!type.isInstance(value)) {
            throw new IllegalStateException("%s has unexpected type: expected=%s, actual=%s".formatted(
                    label,
                    type.getName(),
                    (value == null) ? "null" : value.getClass().getName()));
        }
        return type.cast(value);
    }
}
