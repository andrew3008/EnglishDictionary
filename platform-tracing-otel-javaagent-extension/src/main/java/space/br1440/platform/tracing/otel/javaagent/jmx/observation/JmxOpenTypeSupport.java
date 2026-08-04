package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Общие построители Open MBean типов для шести observation-членов. Каждый вызов строит
 * свежий {@code CompositeData}/{@code TabularData} — при локальном {@code MBeanServer}
 * сериализации нет, приложение получает тот же экземпляр объекта, поэтому кэширование или
 * переиспользование изменяемого {@code TabularDataSupport} между вызовами запрещено:
 * это дало бы приложению torn-итерацию или {@code ConcurrentModificationException} при
 * следующем capture.
 */
public final class JmxOpenTypeSupport {

    private JmxOpenTypeSupport() {
    }

    /** Создаёт свежий упорядоченный набор обязательных item'ов observation frame. */
    public static LinkedHashMap<String, OpenType<?>> observationFrameItems() {
        LinkedHashMap<String, OpenType<?>> items = new LinkedHashMap<>();
        items.put(JmxObservationWireContract.ITEM_SCHEMA_VERSION, SimpleType.INTEGER);
        items.put(JmxObservationWireContract.ITEM_CAPTURE_SEQUENCE, SimpleType.LONG);
        items.put(JmxObservationWireContract.ITEM_AGENT_INSTANCE_ID, SimpleType.STRING);
        items.put(JmxObservationWireContract.ITEM_CAPTURE_STARTED_AT, SimpleType.LONG);
        items.put(JmxObservationWireContract.ITEM_CAPTURE_COMPLETED_AT, SimpleType.LONG);
        return items;
    }

    /** {@code TabularType} с фиксированной строчной схемой {@code (key STRING, value LONG)}. */
    public static TabularType stringLongMapType(String typeName, String rowTypeName) {
        try {
            CompositeType rowType = new CompositeType(rowTypeName, rowTypeName,
                    new String[]{"key", "value"},
                    new String[]{"key", "value"},
                    new OpenType<?>[]{SimpleType.STRING, SimpleType.LONG});
            return new TabularType(typeName, typeName, rowType, new String[]{"key"});
        } catch (OpenDataException e) {
            throw new IllegalStateException("Некорректное описание TabularType " + typeName, e);
        }
    }

    /** Строит свежий {@code TabularData} из карты — без переиспользования между вызовами. */
    public static TabularData stringLongMap(TabularType type, Map<String, Long> values) {
        TabularDataSupport table = new TabularDataSupport(type);
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            try {
                table.put(new CompositeDataSupport(type.getRowType(),
                        new String[]{"key", "value"},
                        new Object[]{entry.getKey(), entry.getValue()}));
            } catch (OpenDataException e) {
                throw new IllegalStateException("Некорректная запись карты для " + type.getTypeName(), e);
            }
        }
        return table;
    }

    /** {@code TabularType} с фиксированной строчной схемой {@code (key STRING, value DOUBLE)}. */
    public static TabularType stringDoubleMapType(String typeName, String rowTypeName) {
        try {
            CompositeType rowType = new CompositeType(rowTypeName, rowTypeName,
                    new String[]{"key", "value"},
                    new String[]{"key", "value"},
                    new OpenType<?>[]{SimpleType.STRING, SimpleType.DOUBLE});
            return new TabularType(typeName, typeName, rowType, new String[]{"key"});
        } catch (OpenDataException e) {
            throw new IllegalStateException("Некорректное описание TabularType " + typeName, e);
        }
    }

    /** Строит свежий {@code TabularData} из карты с {@code double}-значениями. */
    public static TabularData stringDoubleMap(TabularType type, Map<String, Double> values) {
        TabularDataSupport table = new TabularDataSupport(type);
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            try {
                table.put(new CompositeDataSupport(type.getRowType(),
                        new String[]{"key", "value"},
                        new Object[]{entry.getKey(), entry.getValue()}));
            } catch (OpenDataException e) {
                throw new IllegalStateException("Некорректная запись карты для " + type.getTypeName(), e);
            }
        }
        return table;
    }

    /**
     * {@code CompositeType} вместе с именами item'ов в порядке объявления. {@code CompositeType.keySet()}
     * из JDK возвращает имена в алфавитном порядке (внутреннее хранение — {@code TreeMap}), а не в
     * порядке объявления, поэтому сопоставление позиционных значений с именами через {@code keySet()}
     * приводит к сопоставлению значения не с тем item'ом. Схема хранит порядок объявления отдельно,
     * чтобы {@link #compositeData} мог корректно сопоставить значения с именами.
     */
    public record CompositeSchema(CompositeType type, String[] itemNamesInDeclarationOrder) {
    }

    /** Строит {@code CompositeType} с заданными item'ами в порядке передачи. */
    public static CompositeSchema compositeType(String typeName, LinkedHashMap<String, OpenType<?>> items) {
        String[] names = items.keySet().toArray(new String[0]);
        OpenType<?>[] types = items.values().toArray(new OpenType<?>[0]);
        try {
            return new CompositeSchema(new CompositeType(typeName, typeName, names, names, types), names);
        } catch (OpenDataException e) {
            throw new IllegalStateException("Некорректное описание CompositeType " + typeName, e);
        }
    }

    /** Строит {@code CompositeData} — порядок значений обязан совпадать с порядком item'ов схемы. */
    public static CompositeData compositeData(CompositeSchema schema, Object... itemValuesInDeclarationOrder) {
        String[] names = schema.itemNamesInDeclarationOrder();
        if (names.length != itemValuesInDeclarationOrder.length) {
            throw new IllegalArgumentException("Число значений (" + itemValuesInDeclarationOrder.length
                    + ") не совпадает с числом item'ов CompositeType " + schema.type().getTypeName()
                    + " (" + names.length + ")");
        }
        try {
            return new CompositeDataSupport(schema.type(), names, itemValuesInDeclarationOrder);
        } catch (OpenDataException e) {
            throw new IllegalStateException("Некорректные значения для CompositeType " + schema.type().getTypeName(), e);
        }
    }
}
