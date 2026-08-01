package space.br1440.platform.tracing.autoconfigure.identity;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.otel.propagation.RequestIdSupport;

import lombok.experimental.UtilityClass;

/**
 * Bridge для обработки request-id на границе web-модулей.
 */
@UtilityClass
public final class RequestIdBoundarySupport {

    @Nonnull
    public static String resolve(@Nullable String incoming) {
        return RequestIdSupport.resolve(incoming);
    }
}
