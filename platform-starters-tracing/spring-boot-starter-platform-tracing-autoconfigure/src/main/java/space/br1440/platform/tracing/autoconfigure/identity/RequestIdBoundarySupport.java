package space.br1440.platform.tracing.autoconfigure.identity;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.runtime.propagation.RequestIdSupport;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class RequestIdBoundarySupport {

    @Nonnull
    public static String resolve(@Nullable String incoming) {
        return RequestIdSupport.resolve(incoming);
    }
}
