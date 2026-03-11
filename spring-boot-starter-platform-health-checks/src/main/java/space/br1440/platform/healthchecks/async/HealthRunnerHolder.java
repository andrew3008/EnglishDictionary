package space.br1440.platform.healthchecks.async;

import java.util.Optional;

/**
 * <p>Позволяет {@code AsyncHealthRunnerAutoConfiguration} находить внутренний
 * {@link AsyncHealthIndicator} через Actuator-реестр, даже когда индикатор обёрнут
 * декоратором (метрики, трейсинг и т.д.).
 */
public interface HealthRunnerHolder {
    Optional<AsyncHealthIndicator> getRunner();
}
