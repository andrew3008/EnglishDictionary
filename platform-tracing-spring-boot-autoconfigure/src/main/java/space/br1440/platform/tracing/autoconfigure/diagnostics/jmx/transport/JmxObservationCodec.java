package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import lombok.experimental.UtilityClass;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxObservationWireContract;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@UtilityClass
public final class JmxObservationCodec {

    public static CompositeData asComposite(Object raw) {
        if (!(raw instanceof CompositeData composite)) {
            throw new JmxContractViolationException(JmxDiagnosticKind.VALUE_TYPE_MISMATCH);
        }

        return composite;
    }

    public static void requireSchemaVersion(CompositeData composite) {
        int version = getInt(composite, JmxObservationWireContract.ITEM_SCHEMA_VERSION);
        if (version != JmxObservationWireContract.SCHEMA_VERSION) {
            throw new JmxContractViolationException(JmxDiagnosticKind.SCHEMA_VERSION_MISMATCH);
        }
    }

    public static void requireCompositeTypeName(CompositeData composite, String expectedTypeName) {
        String actualTypeName = composite.getCompositeType().getTypeName();
        if (!expectedTypeName.equals(actualTypeName)) {
            throw new JmxContractViolationException(JmxDiagnosticKind.COMPOSITE_TYPE_NAME_MISMATCH);
        }
    }

    public static <T> JmxOwnerState<T> ownerState(CompositeData composite, Supplier<T> valuesWhenPresent) {
        return ownerState(
                composite,
                JmxObservationWireContract.ITEM_OWNER_PRESENT,
                JmxObservationWireContract.ITEM_OWNER_GENERATION,
                valuesWhenPresent
        );
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

    public static int getInt(CompositeData composite, String item) {
        return require(composite, item, Integer.class);
    }

    public static double getDouble(CompositeData composite, String item) {
        return require(composite, item, Double.class);
    }

    public static String getString(CompositeData composite, String item) {
        return require(composite, item, String.class);
    }

    public static CompositeData getComposite(CompositeData composite, String item) {
        return require(composite, item, CompositeData.class);
    }

    public static int getTabularSize(CompositeData composite, String item) {
        return require(composite, item, TabularData.class).size();
    }

    public static Map<String, Long> getStringLongMap(CompositeData composite, String item) {
        TabularData table = require(composite, item, TabularData.class);
        if (table.isEmpty()) {
            return Map.of();
        }

        Map<String, Long> result = new LinkedHashMap<>(table.size());
        for (Object rowObject : table.values()) {
            CompositeData row = (CompositeData) rowObject;
            Object keyRaw = row.get("key");
            Object valueRaw = row.get("value");

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

    public static List<String> getStringArray(CompositeData composite, String item) {
        Object raw = composite.get(item);
        if (raw == null) {
            throw new JmxContractViolationException(JmxDiagnosticKind.MISSING_OBSERVATION_ITEM);
        }

        if (!(raw instanceof String[] array)) {
            throw new JmxContractViolationException(JmxDiagnosticKind.ARRAY_ELEMENT_TYPE_MISMATCH);
        }

        return Collections.unmodifiableList(Arrays.asList(array));
    }

    private static <T> T require(CompositeData composite, String item, Class<T> type) {
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
