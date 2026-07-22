package space.br1440.platform.tracing.core.runtime.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import jakarta.annotation.Nonnull;
import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.api.span.spec.SpanSpecAttributeValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Конвертирует OpenTelemetry {@code Attributes} обратно в платформенные {@link SpanSpecAttributeValue}.
 * <p>
 * <b>Пустые списки:</b> тип элементов восстанавливается из {@link AttributeKey#getType()}.
 * <p>
 * <b>Списки смешанного типа:</b> не поддерживаются и считаются нарушением границы контракта.
 * Платформенные builder'ы не могут породить mixed-type списки, но внешние OTel-экземпляры
 * {@code Attributes} теоретически могут попасть в этот конвертер; такой вход завершается fail-fast
 * через {@link IllegalArgumentException}.
 */
@UtilityClass
public final class SpanSpecAttributeValueConverter {

    @Nonnull
    public static Attributes toAttributes(@Nonnull Map<String, SpanSpecAttributeValue> attributes) {
        AttributesBuilder builder = Attributes.builder();
        for (Map.Entry<String, SpanSpecAttributeValue> entry : attributes.entrySet()) {
            apply(builder, entry.getKey(), entry.getValue());
        }

        return builder.build();
    }

    @Nonnull
    public static Map<String, SpanSpecAttributeValue> fromAttributes(@Nonnull Attributes attributes) {
        Map<String, SpanSpecAttributeValue> map = new LinkedHashMap<>();
        attributes.forEach((key, value) -> map.put(key.getKey(), fromOtelValue(key, value)));
        return Map.copyOf(map);
    }

    private static void apply(AttributesBuilder builder, String key, SpanSpecAttributeValue value) {
        switch (value) {
            case SpanSpecAttributeValue.StringValue sv -> builder.put(key, sv.value());
            case SpanSpecAttributeValue.LongValue lv -> builder.put(key, lv.value());
            case SpanSpecAttributeValue.DoubleValue dv -> builder.put(key, dv.value());
            case SpanSpecAttributeValue.BooleanValue bv -> builder.put(key, bv.value());
            case SpanSpecAttributeValue.StringListValue slv ->
                    builder.put(key, slv.values().toArray(String[]::new));
            case SpanSpecAttributeValue.LongListValue llv ->
                    builder.put(key, llv.values().stream().mapToLong(Long::longValue).toArray());
            case SpanSpecAttributeValue.DoubleListValue dlv ->
                    builder.put(key, dlv.values().stream().mapToDouble(Double::doubleValue).toArray());
            case SpanSpecAttributeValue.BooleanListValue blv -> {
                boolean[] values = new boolean[blv.values().size()];
                for (int i = 0; i < blv.values().size(); i++) {
                    values[i] = blv.values().get(i);
                }
                builder.put(key, values);
            }
        }
    }

    @Nonnull
    static SpanSpecAttributeValue fromOtelValue(@Nonnull AttributeKey<?> key, @Nonnull Object value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        return switch (value) {
            case String s -> SpanSpecAttributeValue.of(s);
            case Long l -> SpanSpecAttributeValue.of(l);
            case Integer i -> SpanSpecAttributeValue.of(i.longValue());
            case Double d -> SpanSpecAttributeValue.of(d);
            case Boolean b -> SpanSpecAttributeValue.of(b);
            case List<?> list -> fromOtelList(key, list);
            default -> SpanSpecAttributeValue.of(String.valueOf(value));
        };
    }

    private static SpanSpecAttributeValue fromOtelList(AttributeKey<?> key, List<?> list) {
        if (list.isEmpty()) {
            return switch (key.getType()) {
                case STRING_ARRAY -> SpanSpecAttributeValue.stringList(List.of());
                case LONG_ARRAY -> SpanSpecAttributeValue.longList(List.of());
                case DOUBLE_ARRAY -> SpanSpecAttributeValue.doubleList(List.of());
                case BOOLEAN_ARRAY -> SpanSpecAttributeValue.booleanList(List.of());
                default -> throw new IllegalArgumentException(
                        "Attribute key is not a list key: " + key.getKey() + " (" + key.getType() + ")");
            };
        }

        Class<?> elementType = requireHomogeneousElementType(list);
        if (elementType == String.class) {
            return SpanSpecAttributeValue.stringList(castList(list, String.class));
        }

        if (elementType == Long.class) {
            return SpanSpecAttributeValue.longList(castList(list, Long.class));
        }

        if (elementType == Double.class) {
            return SpanSpecAttributeValue.doubleList(castList(list, Double.class));
        }

        if (elementType == Boolean.class) {
            return SpanSpecAttributeValue.booleanList(castList(list, Boolean.class));
        }

        throw new IllegalArgumentException("Unsupported list attribute element type: " + elementType);
    }

    private static Class<?> requireHomogeneousElementType(List<?> list) {
        Class<?> first = (list.getFirst() == null) ? null : list.getFirst().getClass();
        for (Object element : list) {
            Class<?> current = (element == null) ? null : element.getClass();
            if (!Objects.equals(first, current)) {
                throw new IllegalArgumentException(
                        "Mixed-type list attribute is not supported: expected " + first
                                + " but found " + current);
            }
        }

        if (first == null) {
            throw new IllegalArgumentException("Null list elements are not supported");
        }

        return first;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> castList(List<?> list, Class<T> type) {
        return (List<T>) list;
    }
}
