package space.br1440.platform.tracing.autoconfigure.reactive.spike;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * SPIKE (CP-1 §21 R2-C3) — НЕ production API, живёт в test-sources.
 *
 * <p>Проверяемая форма поддерживаемого reactive assignment API WebFlux-модуля:
 * приложение, определившее {@code correlationId} асинхронно, оборачивает свою reactive-цепочку,
 * и значение видно downstream-операторам, child-спанам и логам без переноса OTel {@code Scope}
 * через асинхронную границу.</p>
 *
 * <p>Reactor-типы допустимы в WebFlux-модуле и остаются <b>вне</b> {@code platform-tracing-api}.</p>
 */
public interface ReactiveCorrelationOperations {

    /**
     * Исполняет {@code execution} с привязанным {@code correlationId} на всё поддерево подписки.
     *
     * @param correlationId уже canonical бизнес-идентификатор корреляции (не {@code null})
     * @param execution     реактивная цепочка приложения
     */
    <T> Mono<T> withCorrelationId(String correlationId, Mono<T> execution);

    <T> Flux<T> withCorrelationId(String correlationId, Flux<T> execution);
}
