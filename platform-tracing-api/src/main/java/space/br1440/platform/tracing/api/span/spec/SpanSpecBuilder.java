package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;

import java.util.List;

public interface SpanSpecBuilder {

    @Nonnull
    SpanSpecBuilder category(@Nonnull SpanCategory category);

    @Nonnull
    SpanSpecBuilder child();

    @Nonnull
    SpanSpecBuilder root();

    @Nonnull
    SpanSpecBuilder detached();

    @Nonnull
    SpanSpecBuilder linkedTo(@Nonnull RemoteSpanLink... links);

    @Nonnull
    SpanSpecBuilder attribute(@Nonnull String key, @Nonnull String value);

    @Nonnull
    SpanSpecBuilder attribute(@Nonnull String key, long value);

    @Nonnull
    SpanSpecBuilder attribute(@Nonnull String key, double value);

    @Nonnull
    SpanSpecBuilder attribute(@Nonnull String key, boolean value);

    @Nonnull
    SpanSpecBuilder stringListAttribute(@Nonnull String key, @Nonnull List<String> values);

    @Nonnull
    SpanSpecBuilder longListAttribute(@Nonnull String key, @Nonnull List<Long> values);

    @Nonnull
    SpanSpecBuilder doubleListAttribute(@Nonnull String key, @Nonnull List<Double> values);

    @Nonnull
    SpanSpecBuilder booleanListAttribute(@Nonnull String key, @Nonnull List<Boolean> values);

    @Nonnull
    SpanSpecBuilder reason(@Nonnull SpanSpecReason reason);

    @Nonnull
    SpanSpecBuilder reference(@Nonnull String reference);

    @Nonnull
    SpanSpec build();

}
