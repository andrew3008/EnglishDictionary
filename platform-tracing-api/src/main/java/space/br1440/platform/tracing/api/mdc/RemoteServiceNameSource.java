package space.br1440.platform.tracing.api.mdc;

import jakarta.annotation.Nonnull;

import java.util.Optional;

/**
 * SPI read-side contributor для логического имени upstream-сервиса ({@link TracingMdcKeys#REMOTE_SERVICE}).
 * <p>
 * Реализации регистрируются как Spring beans в autoconfigure. Агрегируются
 * {@link space.br1440.platform.tracing.core.mdc.remote.RemoteServiceNameResolver}.
 */
@FunctionalInterface
public interface RemoteServiceNameSource {

    /**
     * @return непустое имя сервиса или {@link Optional#empty()}; never {@code null}
     */
    @Nonnull
    Optional<String> resolve();
}
