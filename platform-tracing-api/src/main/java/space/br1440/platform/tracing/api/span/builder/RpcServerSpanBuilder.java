package space.br1440.platform.tracing.api.span.builder;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.semconv.SemconvKeys;

public interface RpcServerSpanBuilder extends PlatformSpanBuilder<RpcServerSpanBuilder> {

    @Nonnull
    default RpcServerSpanBuilder system(@Nonnull String rpcSystem) {
        return attribute(SemconvKeys.RPC_SYSTEM, rpcSystem);
    }

    @Nonnull
    default RpcServerSpanBuilder service(@Nonnull String service) {
        return attribute(SemconvKeys.RPC_SERVICE, service);
    }

    @Nonnull
    default RpcServerSpanBuilder method(@Nonnull String method) {
        return attribute(SemconvKeys.RPC_METHOD, method);
    }
}
