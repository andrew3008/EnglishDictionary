package space.br1440.platform.tracing.otel.runtime.otel;

import io.opentelemetry.api.trace.SpanKind;
import jakarta.annotation.Nonnull;

import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.api.span.SpanCategory;

@UtilityClass
public final class SpanKinds {

    @Nonnull
    public static SpanKind toSpanKind(@Nonnull SpanCategory category) {
        return switch (category) {
            case HTTP_SERVER, RPC_SERVER -> SpanKind.SERVER;
            case HTTP_CLIENT, DATABASE, RPC_CLIENT -> SpanKind.CLIENT;
            case KAFKA_PRODUCER -> SpanKind.PRODUCER;
            case KAFKA_CONSUMER -> SpanKind.CONSUMER;
            case INTERNAL -> SpanKind.INTERNAL;
        };
    }
}
