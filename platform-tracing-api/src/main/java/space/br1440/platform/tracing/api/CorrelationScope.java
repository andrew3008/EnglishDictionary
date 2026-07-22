package space.br1440.platform.tracing.api;

/**
 * Синхронная область действия бизнес-идентификатора корреляции.
 *
 * <p>Область должна закрываться в том же потоке, в котором была открыта. Повторное закрытие
 * идемпотентно. Для реактивных цепочек используется API WebFlux-модуля.
 */
@FunctionalInterface
public interface CorrelationScope extends AutoCloseable {

    @Override
    void close();

}
