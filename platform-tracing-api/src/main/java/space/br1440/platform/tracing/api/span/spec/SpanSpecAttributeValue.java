package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import java.util.List;
import java.util.Objects;

public sealed interface SpanSpecAttributeValue permits
        SpanSpecAttributeValue.StringValue,
        SpanSpecAttributeValue.LongValue,
        SpanSpecAttributeValue.DoubleValue,
        SpanSpecAttributeValue.BooleanValue,
        SpanSpecAttributeValue.StringListValue,
        SpanSpecAttributeValue.LongListValue,
        SpanSpecAttributeValue.DoubleListValue,
        SpanSpecAttributeValue.BooleanListValue {

    @Nonnull
    static SpanSpecAttributeValue of(@Nonnull String value) {
        Objects.requireNonNull(value, "value");
        return new StringValue(value);
    }

    @Nonnull
    static SpanSpecAttributeValue of(long value) {
        return new LongValue(value);
    }

    @Nonnull
    static SpanSpecAttributeValue of(double value) {
        return new DoubleValue(value);
    }

    @Nonnull
    static SpanSpecAttributeValue of(boolean value) {
        return new BooleanValue(value);
    }

    @Nonnull
    static SpanSpecAttributeValue stringList(@Nonnull List<String> values) {
        return new StringListValue(copyStringList(values));
    }

    @Nonnull
    static SpanSpecAttributeValue longList(@Nonnull List<Long> values) {
        return new LongListValue(copyLongList(values));
    }

    @Nonnull
    static SpanSpecAttributeValue doubleList(@Nonnull List<Double> values) {
        return new DoubleListValue(copyDoubleList(values));
    }

    @Nonnull
    static SpanSpecAttributeValue booleanList(@Nonnull List<Boolean> values) {
        return new BooleanListValue(copyBooleanList(values));
    }

    record StringValue(@Nonnull String value) implements SpanSpecAttributeValue {
        public StringValue {
            Objects.requireNonNull(value, "value");
        }
    }

    record LongValue(long value) implements SpanSpecAttributeValue {
    }

    record DoubleValue(double value) implements SpanSpecAttributeValue {
    }

    record BooleanValue(boolean value) implements SpanSpecAttributeValue {
    }

    record StringListValue(@Nonnull List<String> values) implements SpanSpecAttributeValue {
        public StringListValue {
            Objects.requireNonNull(values, "values");
        }

        @Override
        @Nonnull
        public List<String> values() {
            return List.copyOf(values);
        }
    }

    record LongListValue(@Nonnull List<Long> values) implements SpanSpecAttributeValue {
        public LongListValue {
            Objects.requireNonNull(values, "values");
        }

        @Override
        @Nonnull
        public List<Long> values() {
            return List.copyOf(values);
        }
    }

    record DoubleListValue(@Nonnull List<Double> values) implements SpanSpecAttributeValue {
        public DoubleListValue {
            Objects.requireNonNull(values, "values");
        }

        @Override
        @Nonnull
        public List<Double> values() {
            return List.copyOf(values);
        }
    }

    record BooleanListValue(@Nonnull List<Boolean> values) implements SpanSpecAttributeValue {
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
        return List.copyOf(values.stream().peek(v -> Objects.requireNonNull(v, "list element")).toList());
    }

    private static List<Long> copyLongList(@Nonnull List<Long> values) {
        Objects.requireNonNull(values, "values");
        return List.copyOf(values.stream().peek(v -> Objects.requireNonNull(v, "list element")).toList());
    }

    private static List<Double> copyDoubleList(@Nonnull List<Double> values) {
        Objects.requireNonNull(values, "values");
        return List.copyOf(values.stream().peek(v -> Objects.requireNonNull(v, "list element")).toList());
    }

    private static List<Boolean> copyBooleanList(@Nonnull List<Boolean> values) {
        Objects.requireNonNull(values, "values");
        return List.copyOf(values.stream().peek(v -> Objects.requireNonNull(v, "list element")).toList());
    }
}
