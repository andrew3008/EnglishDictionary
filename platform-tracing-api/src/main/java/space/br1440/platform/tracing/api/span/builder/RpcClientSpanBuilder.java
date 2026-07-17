package space.br1440.platform.tracing.api.span.builder;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.semconv.annotation.RpcSemconvVersion;

@RpcSemconvVersion("1.28.0")
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
