package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import java.util.Map;
import java.util.Objects;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class JmxOpenTypeSupport {

    public static TabularData stringLongMap(TabularType type,
                                            String keyItem,
                                            String valueItem,
                                            Map<String, Long> values) {
        TabularDataSupport table = new TabularDataSupport(type);

        for (Map.Entry<String, Long> entry : values.entrySet()) {
            try {
                table.put(new CompositeDataSupport(type.getRowType(),
                        new String[]{keyItem, valueItem},
                        new Object[]{entry.getKey(), entry.getValue()})
                );
            } catch (OpenDataException e) {
                throw new IllegalStateException("Invalid map entry for " + type.getTypeName(), e);
            }
        }

        return table;
    }

    public static TabularData stringDoubleMap(TabularType type,
                                              String keyItem,
                                              String valueItem,
                                              Map<String, Double> values) {
        TabularDataSupport table = new TabularDataSupport(type);
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            try {
                table.put(
                        new CompositeDataSupport(type.getRowType(),
                        new String[]{keyItem, valueItem},
                        new Object[]{entry.getKey(), entry.getValue()})
                );
            } catch (OpenDataException e) {
                throw new IllegalStateException("Invalid map entry for " + type.getTypeName(), e);
            }
        }

        return table;
    }

    public static CompositeData compositeData(CompositeType type, Map<String, ?> items) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(items, "items");

        try {
            return new CompositeDataSupport(type, items);
        } catch (OpenDataException e) {
            throw new IllegalStateException("Invalid values for CompositeType " + type.getTypeName(), e);
        }
    }
}
