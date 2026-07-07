package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * Semantic transport/protocol builders grouped under {@link ManualTracing#transport()}.
 */
public interface TransportTracing {

    @Nonnull
    HttpTracing http();

    @Nonnull
    DatabaseTracing database();

    @Nonnull
    RpcTracing rpc();

    @Nonnull
    KafkaTracing kafka();

}
