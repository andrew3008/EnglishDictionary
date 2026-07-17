package space.br1440.platform.tracing.api.span.builder;

import jakarta.annotation.Nonnull;

public interface RpcTracing {

    @Nonnull
    RpcServerSpanBuilder server();

    @Nonnull
    RpcClientSpanBuilder client();

}
