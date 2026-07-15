package space.br1440.platform.tracing.api.mdc;

import java.util.Optional;

/**
 * SPI read-side contributor для логического имени upstream-сервиса ({@link TracingMdcKeys#REMOTE_SERVICE}).
 * <p>
 * Реализации регистрируются как Spring beans в autoconfigure (PR-2). Агрегируются
 * {@link space.br1440.platform.tracing.core.mdc.remote.RemoteServiceNameResolver}.
 */
@FunctionalInterface
public interface RemoteServiceNameSource {

    Optional<String> resolve();
}
