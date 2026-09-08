package space.br1440.platform.tracing.autoconfigure.reactive;

import java.util.Objects;
import java.util.function.Function;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.webflux.ReactiveCorrelationOperations;

final class DefaultReactiveCorrelationOperations implements ReactiveCorrelationOperations {

    private final RequestIdentityBoundarySupport boundarySupport;

    DefaultReactiveCorrelationOperations(RequestIdentityBoundarySupport boundarySupport) {
        this.boundarySupport = Objects.requireNonNull(boundarySupport, "boundarySupport");
    }

    @Override
    public <T> Mono<T> withCorrelationId(String correlationId, Mono<T> execution) {
        Objects.requireNonNull(execution, "execution");
        return execution.contextWrite(correlationContextWriter(correlationId));
    }

    @Override
    public <T> Flux<T> withCorrelationId(String correlationId, Flux<T> execution) {
        Objects.requireNonNull(execution, "execution");
        return execution.contextWrite(correlationContextWriter(correlationId));
    }

    private Function<Context, Context> correlationContextWriter(String correlationId) {
        String canonical = resolveCanonicalCorrelationId(correlationId);
        return context -> context.put(
                ReactiveIdentityContextPropagation.KEY,
                withCanonicalCorrelationId(context, canonical)
        );
    }

    private static ReactiveIdentityContextPropagation.IdentityState withCanonicalCorrelationId(Context context,
                                                                                               String canonical) {
        ReactiveIdentityContextPropagation.IdentityState current = context.getOrDefault(
                ReactiveIdentityContextPropagation.KEY,
                ReactiveIdentityContextPropagation.EMPTY
        );

        ReactiveIdentityContextPropagation.IdentityState safeCurrent = Objects.requireNonNullElse(
                current,
                ReactiveIdentityContextPropagation.EMPTY
        );

        return safeCurrent.withCorrelationId(canonical);
    }

    private String resolveCanonicalCorrelationId(String correlationId) {
        return boundarySupport.requireCanonicalCorrelationId(correlationId);
    }
}
