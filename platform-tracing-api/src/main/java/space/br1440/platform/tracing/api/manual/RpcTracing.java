package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

public interface RpcTracing {

    @Nonnull
    RpcServerSpanBuilder server();

    @Nonnull
    RpcClientSpanBuilder client();

}
