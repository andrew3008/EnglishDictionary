package space.br1440.platform.tracing.api.span.builder;

import jakarta.annotation.Nonnull;

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
