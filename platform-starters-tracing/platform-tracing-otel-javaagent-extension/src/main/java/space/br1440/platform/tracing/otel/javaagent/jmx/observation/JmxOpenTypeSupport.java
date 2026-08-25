package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class JmxOpenTypeSupport {

    public static LinkedHashMap<String, OpenType<?>> observationFrameItems() {
        LinkedHashMap<String, OpenType<?>> items = new LinkedHashMap<>();
        items.put(JmxObservationWireContract.ITEM_SCHEMA_VERSION, SimpleType.INTEGER);
        items.put(JmxObservationWireContract.ITEM_CAPTURE_SEQUENCE, SimpleType.LONG);
        items.put(JmxObservationWireContract.ITEM_AGENT_INSTANCE_ID, SimpleType.STRING);
        items.put(JmxObservationWireContract.ITEM_CAPTURE_STARTED_AT, SimpleType.LONG);
        items.put(JmxObservationWireContract.ITEM_CAPTURE_COMPLETED_AT, SimpleType.LONG);
        return items;
    }

    public static TabularType stringLongMapType(String typeName, String rowTypeName) {
        try {
            CompositeType rowType = new CompositeType(rowTypeName, rowTypeName,
                    new String[]{"key", "value"},
                    new String[]{"key", "value"},
                    new OpenType<?>[]{SimpleType.STRING, SimpleType.LONG}
            );
            return new TabularType(typeName, typeName, rowType, new String[]{"key"});
        } catch (OpenDataException e) {
            throw new IllegalStateException("Invalid TabularType description " + typeName, e);
        }
    }

    public static TabularData stringLongMap(TabularType type, Map<String, Long> values) {
        TabularDataSupport table = new TabularDataSupport(type);
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            try {
                table.put(new CompositeDataSupport(type.getRowType(),
                        new String[]{"key", "value"},
                        new Object[]{entry.getKey(), entry.getValue()})
                );
            } catch (OpenDataException e) {
                throw new IllegalStateException("Invalid map entry for " + type.getTypeName(), e);
            }
        }

        return table;
    }

    public static TabularType stringDoubleMapType(String typeName, String rowTypeName) {
        try {
            CompositeType rowType = new CompositeType(rowTypeName, rowTypeName,
                    new String[]{"key", "value"},
                    new String[]{"key", "value"},
                    new OpenType<?>[]{SimpleType.STRING, SimpleType.DOUBLE}
            );
            return new TabularType(typeName, typeName, rowType, new String[]{"key"});
        } catch (OpenDataException e) {
            throw new IllegalStateException("Invalid TabularType description " + typeName, e);
        }
    }

    public static TabularData stringDoubleMap(TabularType type, Map<String, Double> values) {
        TabularDataSupport table = new TabularDataSupport(type);
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            try {
                table.put(new CompositeDataSupport(type.getRowType(),
                        new String[]{"key", "value"},
                        new Object[]{entry.getKey(), entry.getValue()})
                );
            } catch (OpenDataException e) {
                throw new IllegalStateException("Invalid map entry for " + type.getTypeName(), e);
            }
        }

        return table;
    }

    public record CompositeSchema(CompositeType type, String[] itemNamesInDeclarationOrder) {
    }

    public static CompositeSchema compositeType(String typeName, LinkedHashMap<String, OpenType<?>> items) {
        String[] names = items.keySet().toArray(new String[0]);
        OpenType<?>[] types = items.values().toArray(new OpenType<?>[0]);
        try {
            return new CompositeSchema(new CompositeType(typeName, typeName, names, names, types), names);
        } catch (OpenDataException e) {
            throw new IllegalStateException("Invalid CompositeType description " + typeName, e);
        }
    }

    public static CompositeData compositeData(CompositeSchema schema, Object... itemValuesInDeclarationOrder) {
        String[] names = schema.itemNamesInDeclarationOrder();
        if (names.length != itemValuesInDeclarationOrder.length) {
            throw new IllegalArgumentException(
                    "Number of values (%d) does not match the number of items in CompositeType %s (%d)"
                            .formatted(itemValuesInDeclarationOrder.length, schema.type().getTypeName(), names.length)
            );
        }

        try {
            return new CompositeDataSupport(schema.type(), names, itemValuesInDeclarationOrder);
        } catch (OpenDataException e) {
            throw new IllegalStateException("Invalid values for CompositeType " + schema.type().getTypeName(), e);
        }
    }
}
