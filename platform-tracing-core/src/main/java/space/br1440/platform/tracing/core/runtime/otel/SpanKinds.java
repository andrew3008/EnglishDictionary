package space.br1440.platform.tracing.core.runtime.otel;

import io.opentelemetry.api.trace.SpanKind;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanCategory;

/**
 * Единое сопоставление платформенной {@link SpanCategory} и OpenTelemetry {@link SpanKind}.
 * <p>
 * Используется и процедурным {@code DefaultPlatformTracing}, и типизированными builder'ами —
 * единый источник истины, чтобы kind не расходился между путями создания span'ов.
 */
public final class SpanKinds {

    private SpanKinds() {
        // utility-класс
    }

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
