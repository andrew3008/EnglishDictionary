package space.br1440.platform.tracing.api.span.spec;

import java.util.List;
import java.util.Objects;
import jakarta.annotation.Nonnull;

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
        return new StringListValue(values);
    }

    @Nonnull
    static SpanSpecAttributeValue longList(@Nonnull List<Long> values) {
        return new LongListValue(values);
    }

    @Nonnull
    static SpanSpecAttributeValue doubleList(@Nonnull List<Double> values) {
        return new DoubleListValue(values);
    }

    @Nonnull
    static SpanSpecAttributeValue booleanList(@Nonnull List<Boolean> values) {
        return new BooleanListValue(values);
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
            values = List.copyOf(Objects.requireNonNull(values, "values"));
        }
    }

    record LongListValue(@Nonnull List<Long> values) implements SpanSpecAttributeValue {
        public LongListValue {
            values = List.copyOf(Objects.requireNonNull(values, "values"));
        }
    }

    record DoubleListValue(@Nonnull List<Double> values) implements SpanSpecAttributeValue {
        public DoubleListValue {
            values = List.copyOf(Objects.requireNonNull(values, "values"));
        }
    }

    record BooleanListValue(@Nonnull List<Boolean> values) implements SpanSpecAttributeValue {
        public BooleanListValue {
            values = List.copyOf(Objects.requireNonNull(values, "values"));
        }
    }
}
