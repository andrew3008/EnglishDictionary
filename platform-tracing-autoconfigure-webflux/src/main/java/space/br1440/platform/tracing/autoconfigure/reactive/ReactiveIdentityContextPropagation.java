package space.br1440.platform.tracing.autoconfigure.reactive;

import java.util.Objects;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;

import space.br1440.platform.tracing.api.CorrelationScope;
import space.br1440.platform.tracing.autoconfigure.support.RequestIdentityBoundarySupport;

/** Связывает immutable identity из Reactor Context с внутренним execution-store. */
final class ReactiveIdentityContextPropagation implements SmartInitializingSingleton, DisposableBean {

    static final String KEY = "platform.identity";
    static final IdentityState EMPTY = new IdentityState(null, null);

    private final IdentityThreadLocalAccessor accessor;

    ReactiveIdentityContextPropagation(RequestIdentityBoundarySupport boundarySupport) {
        this.accessor = new IdentityThreadLocalAccessor(boundarySupport);
    }

    @Override
    public void afterSingletonsInstantiated() {
        ContextRegistry registry = ContextRegistry.getInstance();
        registry.removeThreadLocalAccessor(KEY);
        registry.registerThreadLocalAccessor(accessor);
    }

    @Override
    public void destroy() {
        ContextRegistry registry = ContextRegistry.getInstance();
        boolean ownsRegistration = registry.getThreadLocalAccessors().stream()
                .anyMatch(candidate -> candidate == accessor);
        if (ownsRegistration) {
            registry.removeThreadLocalAccessor(KEY);
        }
        accessor.clearBridgeScope();
    }

    record IdentityState(String requestId, String correlationId) {

        IdentityState withRequestId(String value) {
            return new IdentityState(value, correlationId);
        }

        IdentityState withCorrelationId(String value) {
            return new IdentityState(requestId, value);
        }
    }

    private static final class IdentityThreadLocalAccessor implements ThreadLocalAccessor<IdentityState> {

        private final RequestIdentityBoundarySupport boundarySupport;
        private final ThreadLocal<CorrelationScope> bridgeScope = new ThreadLocal<>();

        private IdentityThreadLocalAccessor(RequestIdentityBoundarySupport boundarySupport) {
            this.boundarySupport = Objects.requireNonNull(boundarySupport, "boundarySupport");
        }

        @Override
        public Object key() {
            return KEY;
        }

        @Override
        public IdentityState getValue() {
            IdentityState state = new IdentityState(
                    boundarySupport.requestId().orElse(null),
                    boundarySupport.correlationId().orElse(null)
            );
            return state.equals(EMPTY) ? null : state;
        }

        @Override
        public void setValue(IdentityState value) {
            clearBridgeScope();
            bridgeScope.set(openScope(value));
        }

        @Override
        public void setValue() {
            clearBridgeScope();
        }

        @Override
        public void restore(IdentityState previousValue) {
            setValue(previousValue);
        }

        @Override
        public void restore() {
            clearBridgeScope();
        }

        private CorrelationScope openScope(IdentityState state) {
            CorrelationScope requestScope = state.requestId() == null
                    ? null
                    : boundarySupport.openRequestScope(state.requestId());
            try {
                CorrelationScope correlationScope = state.correlationId() == null
                        ? null
                        : boundarySupport.openCorrelationScope(state.correlationId());
                return () -> {
                    if (correlationScope != null) {
                        correlationScope.close();
                    }
                    if (requestScope != null) {
                        requestScope.close();
                    }
                };
            } catch (RuntimeException | Error failure) {
                if (requestScope != null) {
                    requestScope.close();
                }
                throw failure;
            }
        }

        private void clearBridgeScope() {
            CorrelationScope scope = bridgeScope.get();
            if (scope != null) {
                scope.close();
                bridgeScope.remove();
            }
        }
    }
}
