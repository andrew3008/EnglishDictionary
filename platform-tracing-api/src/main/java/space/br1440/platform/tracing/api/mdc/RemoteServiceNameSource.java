package space.br1440.platform.tracing.api.mdc;

import java.util.Optional;

/**
 * SPI для read-side contributors, предоставляющих логическое имя upstream-сервиса.
 * <p>
 * Реализации регистрируются как Spring-бины ({@code @Bean @Order}) в autoconfigure-модулях.
 * {@code RemoteServiceNameResolver} (core) вызывает зарегистрированные sources в порядке
 * {@code @Order}, встроенный fallback через trace-scoped mirror всегда последний.
 * <p>
 * Паттерн аналогичен {@code InboundTraceControlExtractor} в {@code api.propagation.control}:
 * интерфейс-контракт живёт в api, реализации — в core / autoconfigure.
 *
 * @see space.br1440.platform.tracing.api.mdc.TracingMdcKeys#REMOTE_SERVICE
 */
@FunctionalInterface
public interface RemoteServiceNameSource {

    /**
     * Возвращает имя удалённого сервиса, если оно доступно в текущем контексте выполнения.
     *
     * @return непустое имя сервиса или {@link Optional#empty()}
     */
    Optional<String> resolve();
}
