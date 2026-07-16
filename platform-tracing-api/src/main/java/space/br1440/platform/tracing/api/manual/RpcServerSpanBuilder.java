package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.semconv.annotation.RpcSemconvVersion;

/**
 * Семантический построитель RPC server под {@link RpcTracing#server()}.
 */
@RpcSemconvVersion("1.28.0")
public interface RpcServerSpanBuilder extends ManualSpanBuilder<RpcServerSpanBuilder> {

    @Nonnull
    RpcServerSpanBuilder system(@Nonnull String rpcSystem);

    @Nonnull
    RpcServerSpanBuilder service(@Nonnull String service);

    @Nonnull
    RpcServerSpanBuilder method(@Nonnull String method);

}
