package space.br1440.platform.tracing.autoconfigure.integrations.reactor;

import java.util.Objects;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;

public final class OtelReactorContextPropagation implements SmartInitializingSingleton, DisposableBean {

    private final OtelContextThreadLocalAccessor accessor = new OtelContextThreadLocalAccessor();

    @Override
    public void afterSingletonsInstantiated() {
        ContextRegistry registry = ContextRegistry.getInstance();
        if (alreadyRegistered(registry)) {
            return;
        }

        registry.registerThreadLocalAccessor(accessor);
    }

    @Override
    public void destroy() {
        ContextRegistry registry = ContextRegistry.getInstance();
        boolean ownsRegistration = registry.getThreadLocalAccessors().stream()
                .anyMatch(candidate -> candidate == accessor);

        if (ownsRegistration) {
            registry.removeThreadLocalAccessor(OtelContextThreadLocalAccessor.KEY);
        }

        accessor.clearScopes();
    }

    public static Object reactorContextKey() {
        return OtelContextThreadLocalAccessor.KEY;
    }

    public static Object snapshotRecordingContext() {
        Context current = Context.current();
        if (current == Context.root()) {
            return null;
        }

        Span span = Span.fromContext(current);
        if (!span.getSpanContext().isValid() || !span.isRecording()) {
            return null;
        }

        return current;
    }

    public static void detachEndedCurrentContext() {
        Span span = Span.current();
        if (!span.getSpanContext().isValid() || span.isRecording()) {
            return;
        }

        Context.root().makeCurrent();
    }

    private static boolean alreadyRegistered(ContextRegistry registry) {
        for (ThreadLocalAccessor<?> candidate : registry.getThreadLocalAccessors()) {
            Object key = candidate.key();
            if (Objects.equals(OtelContextThreadLocalAccessor.KEY, key)
                    || Context.class.equals(key)) {
                return true;
            }
        }

        return false;
    }
}
