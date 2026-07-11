package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * Семантический построитель RPC client под {@link RpcTracing#client()}.
 */
public interface RpcClientSpanBuilder extends ManualSpanBuilder<RpcClientSpanBuilder> {

    @Nonnull
    RpcClientSpanBuilder system(@Nonnull String rpcSystem);

    @Nonnull
    RpcClientSpanBuilder service(@Nonnull String service);

    @Nonnull
    RpcClientSpanBuilder method(@Nonnull String method);

    @Nonnull
    RpcClientSpanBuilder serverAddress(@Nonnull String address);
}
