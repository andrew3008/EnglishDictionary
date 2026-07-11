package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * Семантический построитель RPC server под {@link RpcTracing#server()}.
 */
public interface RpcServerSpanBuilder extends PlatformSpanBuilder<RpcServerSpanBuilder> {

    @Nonnull
    RpcServerSpanBuilder system(@Nonnull String rpcSystem);

    @Nonnull
    RpcServerSpanBuilder service(@Nonnull String service);

    @Nonnull
    RpcServerSpanBuilder method(@Nonnull String method);
}
