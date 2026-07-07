package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import java.util.List;
import java.util.Objects;

/**
 * Whitelist attribute value for {@link SpanSpec}.
 * <p>
 * Supports only OpenTelemetry-compatible scalar and homogeneous list types.
 */
public sealed interface SpanAttributeValue permits
        SpanAttributeValue.StringValue,
        SpanAttributeValue.LongValue,
        SpanAttributeValue.DoubleValue,
        SpanAttributeValue.BooleanValue,
        SpanAttributeValue.StringListValue,
        SpanAttributeValue.LongListValue,
        SpanAttributeValue.DoubleListValue,
        SpanAttributeValue.BooleanListValue {

    @Nonnull
    static SpanAttributeValue of(@Nonnull String value) {
        Objects.requireNonNull(value, "value");
        return new StringValue(value);
    }

    @Nonnull
    static SpanAttributeValue of(long value) {
        return new LongValue(value);
    }

    @Nonnull
    static SpanAttributeValue of(double value) {
        return new DoubleValue(value);
    }

    @Nonnull
    static SpanAttributeValue of(boolean value) {
        return new BooleanValue(value);
    }

    @Nonnull
    static SpanAttributeValue stringList(@Nonnull List<String> values) {
        return new StringListValue(copyStringList(values));
    }

    @Nonnull
    static SpanAttributeValue longList(@Nonnull List<Long> values) {
        return new LongListValue(copyLongList(values));
    }

    @Nonnull
    static SpanAttributeValue doubleList(@Nonnull List<Double> values) {
        return new DoubleListValue(copyDoubleList(values));
    }

    @Nonnull
    static SpanAttributeValue booleanList(@Nonnull List<Boolean> values) {
        return new BooleanListValue(copyBooleanList(values));
    }

    record StringValue(@Nonnull String value) implements SpanAttributeValue {
        public StringValue {
            Objects.requireNonNull(value, "value");
        }
    }

    record LongValue(long value) implements SpanAttributeValue {
    }

    record DoubleValue(double value) implements SpanAttributeValue {
    }

    record BooleanValue(boolean value) implements SpanAttributeValue {
    }

    record StringListValue(@Nonnull List<String> values) implements SpanAttributeValue {
        public StringListValue {
            Objects.requireNonNull(values, "values");
        }

        @Override
        @Nonnull
        public List<String> values() {
            return List.copyOf(values);
        }
    }

    record LongListValue(@Nonnull List<Long> values) implements SpanAttributeValue {
        public LongListValue {
            Objects.requireNonNull(values, "values");
        }

        @Override
        @Nonnull
        public List<Long> values() {
            return List.copyOf(values);
        }
    }

    record DoubleListValue(@Nonnull List<Double> values) implements SpanAttributeValue {
        public DoubleListValue {
            Objects.requireNonNull(values, "values");
        }

        @Override
        @Nonnull
        public List<Double> values() {
            return List.copyOf(values);
        }
    }

    record BooleanListValue(@Nonnull List<Boolean> values) implements SpanAttributeValue {
        public BooleanListValue {
            Objects.requireNonNull(values, "values");
        }

        @Override
        @Nonnull
        public List<Boolean> values() {
            return List.copyOf(values);
        }
    }

    private static List<String> copyStringList(@Nonnull List<String> values) {
        Objects.requireNonNull(values, "values");
        return List.copyOf(values.stream().map(v -> {
            Objects.requireNonNull(v, "list element");
            return v;
        }).toList());
    }

    private static List<Long> copyLongList(@Nonnull List<Long> values) {
        Objects.requireNonNull(values, "values");
        return List.copyOf(values.stream().map(v -> {
            Objects.requireNonNull(v, "list element");
            return v;
        }).toList());
    }

    private static List<Double> copyDoubleList(@Nonnull List<Double> values) {
        Objects.requireNonNull(values, "values");
        return List.copyOf(values.stream().map(v -> {
            Objects.requireNonNull(v, "list element");
            return v;
        }).toList());
    }

    private static List<Boolean> copyBooleanList(@Nonnull List<Boolean> values) {
        Objects.requireNonNull(values, "values");
        return List.copyOf(values.stream().map(v -> {
            Objects.requireNonNull(v, "list element");
            return v;
        }).toList());
    }
}
