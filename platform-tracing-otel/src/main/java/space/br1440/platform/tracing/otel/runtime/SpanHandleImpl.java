package space.br1440.platform.tracing.otel.runtime;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.otel.runtime.otel.scope.OwningSpanScope;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class SpanHandleImpl implements SpanHandle {

    private final OwningSpanScope scope;
    private final Set<Throwable> recordedThrowables = Collections.newSetFromMap(new IdentityHashMap<>());

    private SpanHandleImpl(@Nonnull OwningSpanScope scope) {
        this.scope = scope;
    }

    @Nonnull
    public static SpanHandle wrap(@Nonnull OwningSpanScope scope) {
        return new SpanHandleImpl(scope);
    }

    @Override
    public void recordException(@Nullable Throwable throwable) {
        if (throwable == null) {
            return;
        }

        if (!recordedThrowables.add(throwable)) {
            return;
        }

        scope.recordException(throwable);
    }

    @Override
    public void close() {
        scope.close();
    }
}
