package space.br1440.platform.tracing.runtime.propagation.control;

import java.util.function.Supplier;

import jakarta.annotation.Nullable;

public interface OutboundApplicationIdentitySource {

    static OutboundApplicationIdentitySource of(Supplier<String> requestIdSupplier, Supplier<String> correlationIdSupplier) {
        return new OutboundApplicationIdentitySource() {

            @Override
            @Nullable
            public String currentRequestId() {
                return requestIdSupplier.get();
            }

            @Override
            @Nullable
            public String currentCorrelationId() {
                return correlationIdSupplier.get();
            }
        };
    }

    @Nullable
    String currentRequestId();

    @Nullable
    String currentCorrelationId();

}
