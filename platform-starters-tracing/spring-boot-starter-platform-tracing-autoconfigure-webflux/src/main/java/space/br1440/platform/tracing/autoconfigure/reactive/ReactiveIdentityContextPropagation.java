package space.br1440.platform.tracing.autoconfigure.reactive;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

final class ReactiveIdentityContextPropagation implements SmartInitializingSingleton, DisposableBean {

    static final String KEY = "platform.identity";
    static final IdentityState EMPTY = new IdentityState(null, null, false, false);

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

        accessor.clearBridgeScopes();
    }

    record IdentityState(String requestId, String correlationId, boolean forceTrace, boolean qaTrace) {

        IdentityState withRequestId(String value) {
            return new IdentityState(value, correlationId, forceTrace, qaTrace);
        }

        IdentityState withCorrelationId(String value) {
            return new IdentityState(requestId, value, forceTrace, qaTrace);
        }

        IdentityState withTraceControl(boolean forceTraceValue, boolean qaTraceValue) {
            return new IdentityState(requestId, correlationId, forceTraceValue, qaTraceValue);
        }
    }

    private static final class IdentityThreadLocalAccessor implements ThreadLocalAccessor<IdentityState> {

        private final RequestIdentityBoundarySupport boundarySupport;
        private final ThreadLocal<Deque<BridgeFrame>> bridgeScopes =
                ThreadLocal.withInitial(ArrayDeque::new);

        private IdentityThreadLocalAccessor(RequestIdentityBoundarySupport boundarySupport) {
            this.boundarySupport = Objects.requireNonNull(boundarySupport, "boundarySupport");
        }

        @Override
        @NonNull
        public Object key() {
            return KEY;
        }

        @Override
        @Nullable
        public IdentityState getValue() {
            IdentityState state = new IdentityState(
                    boundarySupport.requestId().orElse(null),
                    boundarySupport.correlationId().orElse(null),
                    boundarySupport.forceTrace(),
                    boundarySupport.qaTrace()
            );
            return state.equals(EMPTY) ? null : state;
        }

        @Override
        public void setValue(@NonNull IdentityState value) {
            bridgeScopes.get().push(new BridgeFrame(openScope(value)));
        }

        @Override
        public void setValue() {
            bridgeScopes.get().push(BridgeFrame.EMPTY);
        }

        @Override
        public void restore(@Nullable IdentityState previousValue) {
            closeBridgeScope();
            if (previousValue != null && !previousValue.equals(EMPTY) && bridgeScopes.get().isEmpty()) {
                setValue(previousValue);
            }
        }

        @Override
        public void restore() {
            closeBridgeScope();
        }

        @SuppressWarnings("resource")
        private CorrelationScope openScope(IdentityState state) {
            CorrelationScope requestScope = (state.requestId() == null)
                    ? null
                    : boundarySupport.openRequestScope(state.requestId(), state.forceTrace(), state.qaTrace());
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

        private void closeBridgeScope() {
            Deque<BridgeFrame> scopes = bridgeScopes.get();
            if (scopes.isEmpty()) {
                return;
            }

            scopes.pop().close();
            if (scopes.isEmpty()) {
                bridgeScopes.remove();
            }
        }

        private void clearBridgeScopes() {
            Deque<BridgeFrame> scopes = bridgeScopes.get();
            while (!scopes.isEmpty()) {
                scopes.pop().close();
            }

            bridgeScopes.remove();
        }
    }

    private record BridgeFrame(CorrelationScope scope) {

        private static final BridgeFrame EMPTY = new BridgeFrame(null);

        private void close() {
            if (scope != null) {
                scope.close();
            }
        }
    }
}
