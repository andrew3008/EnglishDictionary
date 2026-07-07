package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.semconv.RpcSemconvVersion;

/**
 * RPC transport tracing entry (Slice 3C).
 */
@RpcSemconvVersion("1.28.0")
public interface RpcTracing {

    @Nonnull
    RpcServerSpanBuilder server();

    @Nonnull
    RpcClientSpanBuilder client();
}
