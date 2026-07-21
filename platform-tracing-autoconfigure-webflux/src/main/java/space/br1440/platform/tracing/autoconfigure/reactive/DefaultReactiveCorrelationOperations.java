package space.br1440.platform.tracing.autoconfigure.reactive;

import java.util.Objects;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import space.br1440.platform.tracing.autoconfigure.support.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.webflux.ReactiveCorrelationOperations;

final class DefaultReactiveCorrelationOperations implements ReactiveCorrelationOperations {

    private final RequestIdentityBoundarySupport boundarySupport;

    DefaultReactiveCorrelationOperations(RequestIdentityBoundarySupport boundarySupport) {
        this.boundarySupport = Objects.requireNonNull(boundarySupport, "boundarySupport");
    }

    @Override
    public <T> Mono<T> withCorrelationId(String correlationId, Mono<T> execution) {
        String canonical = boundarySupport.requireCanonicalCorrelationId(correlationId);
        Objects.requireNonNull(execution, "execution");
        return execution.contextWrite(context -> context.put(
                ReactiveIdentityContextPropagation.KEY,
                context.getOrDefault(
                                ReactiveIdentityContextPropagation.KEY,
                                ReactiveIdentityContextPropagation.EMPTY)
                        .withCorrelationId(canonical)
        ));
    }

    @Override
    public <T> Flux<T> withCorrelationId(String correlationId, Flux<T> execution) {
        String canonical = boundarySupport.requireCanonicalCorrelationId(correlationId);
        Objects.requireNonNull(execution, "execution");
        return execution.contextWrite(context -> context.put(
                ReactiveIdentityContextPropagation.KEY,
                context.getOrDefault(
                                ReactiveIdentityContextPropagation.KEY,
                                ReactiveIdentityContextPropagation.EMPTY)
                        .withCorrelationId(canonical)
        ));
    }
}
