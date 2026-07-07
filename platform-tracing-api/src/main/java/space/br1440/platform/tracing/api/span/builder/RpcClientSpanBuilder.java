package space.br1440.platform.tracing.api.span.builder;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.semconv.SemconvKeys;

public interface RpcClientSpanBuilder extends PlatformSpanBuilder<RpcClientSpanBuilder> {

    @Nonnull
    default RpcClientSpanBuilder system(@Nonnull String rpcSystem) {
        return attribute(SemconvKeys.RPC_SYSTEM, rpcSystem);
    }

    @Nonnull
    default RpcClientSpanBuilder service(@Nonnull String service) {
        return attribute(SemconvKeys.RPC_SERVICE, service);
    }

    @Nonnull
    default RpcClientSpanBuilder method(@Nonnull String method) {
        return attribute(SemconvKeys.RPC_METHOD, method);
    }

    @Nonnull
    default RpcClientSpanBuilder serverAddress(@Nonnull String address) {
        return attribute(SemconvKeys.SERVER_ADDRESS, address);
    }
}
