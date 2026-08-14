package space.br1440.platform.tracing.api.mdc;

import jakarta.annotation.Nonnull;

import java.util.Optional;

/**
 * SPI read-side contributor для логического имени upstream-сервиса ({@link TracingMdcKeys#REMOTE_SERVICE}).
 * <p>
 * Реализации регистрируются как Spring beans в autoconfigure.
 * Агрегируются {@code space.br1440.platform.tracing.otel.mdc.remote.RemoteServiceNameResolver}.
 */
@FunctionalInterface
public interface RemoteServiceNameSource {

    /**
     * @return опциональное имя сервиса
     */
    @Nonnull
    Optional<String> resolve();

}
