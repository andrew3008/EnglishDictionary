package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import space.br1440.platform.tracing.autoconfigure.agent.AgentInstanceIdValidator;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class JmxObservationCodec {

    public static CompositeData asComposite(Object raw) {
        if (!(raw instanceof CompositeData composite)) {
            throw new JmxContractViolationException(JmxDiagnosticKind.VALUE_TYPE_MISMATCH);
        }

        return composite;
    }

    public static void requireCompositeTypeName(CompositeData composite, String expectedTypeName) {
        String actualTypeName = composite.getCompositeType().getTypeName();
        if (!expectedTypeName.equals(actualTypeName)) {
            throw new JmxContractViolationException(JmxDiagnosticKind.COMPOSITE_TYPE_NAME_MISMATCH);
        }
    }

    public static <T> JmxOwnerState<T> ownerState(CompositeData composite, String presentItem, String generationItem, Supplier<T> valuesWhenPresent) {
        boolean present = getBoolean(composite, presentItem);
        long generation = getLong(composite, generationItem);
        if (!present) {
            return new JmxOwnerState.Absent<>(generation);
        }

        if (generation <= 0) {
            throw new JmxContractViolationException(JmxDiagnosticKind.OWNER_GENERATION_INVALID);
        }

        return new JmxOwnerState.Present<>(valuesWhenPresent.get(), generation);
    }

    public static boolean getBoolean(CompositeData composite, String item) {
        return require(composite, item, Boolean.class);
    }

    public static long getLong(CompositeData composite, String item) {
        return require(composite, item, Long.class);
    }

    public static long getPositiveLong(CompositeData composite, String item) {
        long value = getLong(composite, item);
        if (value <= 0L) {
            throw new JmxContractViolationException(JmxDiagnosticKind.VALUE_SEMANTICS_VIOLATION);
        }

        return value;
    }

    public static int getInt(CompositeData composite, String item) {
        return require(composite, item, Integer.class);
    }

    public static double getDouble(CompositeData composite, String item) {
        return require(composite, item, Double.class);
    }

    public static String getString(CompositeData composite, String item) {
        return require(composite, item, String.class);
    }

    public static String getAgentInstanceId(CompositeData composite, String item) {
        String value = getString(composite, item);
        try {
            return AgentInstanceIdValidator.requireCanonical(value, item);
        } catch (IllegalArgumentException exception) {
            throw new JmxContractViolationException(JmxDiagnosticKind.VALUE_SEMANTICS_VIOLATION);
        }
    }

    public static <E extends Enum<E>> E getEnum(CompositeData composite, String item, Class<E> enumType) {
        String raw = require(composite, item, String.class);
        if (raw.isBlank()) {
            throw new JmxContractViolationException(JmxDiagnosticKind.VALUE_SEMANTICS_VIOLATION);
        }
        try {
            return Enum.valueOf(enumType, raw);
        } catch (IllegalArgumentException notCanonical) {
            throw new JmxContractViolationException(JmxDiagnosticKind.VALUE_SEMANTICS_VIOLATION);
        }
    }

    public static CompositeData getComposite(CompositeData composite, String item) {
        return require(composite, item, CompositeData.class);
    }

    public static int getTabularSize(CompositeData composite, String item) {
        return require(composite, item, TabularData.class).size();
    }

    public static Map<String, Long> getStringLongMap(
            CompositeData composite,
            String item,
            String keyItem,
            String valueItem
    ) {
        TabularData table = require(composite, item, TabularData.class);
        if (table.isEmpty()) {
            return Map.of();
        }

        Map<String, Long> result = new LinkedHashMap<>(table.size());
        for (Object rowObject : table.values()) {
            CompositeData row = (CompositeData) rowObject;
            Object keyRaw = row.get(keyItem);
            Object valueRaw = row.get(valueItem);

            if (!(keyRaw instanceof String key)) {
                throw new JmxContractViolationException(JmxDiagnosticKind.MAP_KEY_TYPE_MISMATCH);
            }

            if (!(valueRaw instanceof Long value)) {
                throw new JmxContractViolationException(JmxDiagnosticKind.MAP_VALUE_TYPE_MISMATCH);
            }

            result.put(key, value);
        }

        return Collections.unmodifiableMap(result);
    }

    private static <T> T require(CompositeData composite, String item, Class<T> type) {
        if (!composite.containsKey(item)) {
            throw new JmxContractViolationException(JmxDiagnosticKind.MISSING_OBSERVATION_ITEM);
        }

        Object raw = composite.get(item);
        if (raw == null) {
            throw new JmxContractViolationException(JmxDiagnosticKind.MISSING_OBSERVATION_ITEM);
        }

        if (!type.isInstance(raw)) {
            throw new JmxContractViolationException(JmxDiagnosticKind.VALUE_TYPE_MISMATCH);
        }

        return type.cast(raw);
    }
}
