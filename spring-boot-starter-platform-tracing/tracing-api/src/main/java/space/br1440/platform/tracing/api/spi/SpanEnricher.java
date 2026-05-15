package space.br1440.platform.tracing.api.spi;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.span.SpanScope;

/**
 * Обогащает каждый создаваемый span дополнительными атрибутами, специфичными для конкретного сервиса
 * или предметной области.
 * <p>
 * Реализации регистрируются как Spring-бины и вызываются на этапе старта span'а. Метод должен быть
 * быстрым (порядок единиц микросекунд) и не должен блокироваться.
 * <p>
 * Типичный пример использования: пробрасывание идентификатора арендатора, версии feature-flag'а,
 * идентификатора корреляции из вышестоящей системы.
 */
@FunctionalInterface
public interface SpanEnricher {

    /**
     * Добавляет атрибуты к стартующему span'у.
     */
    void enrich(@Nonnull SpanScope spanScope);
}
