package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * Семантические построители транспортов/протоколов, сгруппированные spans
 * {@link space.br1440.platform.tracing.api.span.SpanFactory#transport()}
 */
public interface TransportTracing {

    @Nonnull
    HttpTracing http();

    @Nonnull
    DatabaseSpanBuilder database();

    @Nonnull
    RpcTracing rpc();

    @Nonnull
    KafkaTracing kafka();

}
