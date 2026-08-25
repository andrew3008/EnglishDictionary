package space.br1440.platform.tracing.autoconfigure.integrations.reactor;

import java.util.ArrayDeque;
import java.util.Deque;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import io.micrometer.context.ThreadLocalAccessor;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

final class OtelContextThreadLocalAccessor implements ThreadLocalAccessor<Context> {

    static final String KEY = "platform.otel.context";

    private final ThreadLocal<Deque<Scope>> scopes = ThreadLocal.withInitial(ArrayDeque::new);

    @Override
    @NonNull
    public Object key() {
        return KEY;
    }

    @Override
    @Nullable
    public Context getValue() {
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

    @Override
    public void setValue(@NonNull Context value) {
        scopes.get().push(value.makeCurrent());
    }

    @Override
    public void setValue() {
        Span current = Span.current();
        if (current.getSpanContext().isValid() && current.isRecording()) {
            scopes.get().push(Scope.noop());
            return;
        }

        scopes.get().push(Context.root().makeCurrent());
    }

    @Override
    public void restore(@Nullable Context previousValue) {
        closeTop();
        if (previousValue != null && !previousValue.equals(Context.root()) && scopes.get().isEmpty()) {
            setValue(previousValue);
        }
    }

    @Override
    public void restore() {
        closeTop();
    }

    void clearScopes() {
        Deque<Scope> open = scopes.get();
        while (!open.isEmpty()) {
            open.pop().close();
        }
        scopes.remove();
    }

    private void closeTop() {
        Deque<Scope> open = scopes.get();
        if (open.isEmpty()) {
            return;
        }

        open.pop().close();
        if (open.isEmpty()) {
            scopes.remove();
        }
    }
}
