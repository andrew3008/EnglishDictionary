package space.br1440.platform.tracing.webflux;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Реактивная привязка business correlationId к каждой подписке. */
public interface ReactiveCorrelationOperations {

    <T> Mono<T> withCorrelationId(String correlationId, Mono<T> execution);

    <T> Flux<T> withCorrelationId(String correlationId, Flux<T> execution);
}
