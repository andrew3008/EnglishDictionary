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
    private Set<Throwable> recordedThrowables;
    private boolean closed;

    private SpanHandleImpl(@Nonnull OwningSpanScope scope) {
        this.scope = scope;
    }

    @Nonnull
    public static SpanHandle wrap(@Nonnull OwningSpanScope scope) {
        return new SpanHandleImpl(scope);
    }

    @Override
    public void recordException(@Nullable Throwable throwable) {
        if (throwable == null || closed) {
            return;
        }

        if (!scope.isRecording()) {
            scope.recordException(throwable);
            return;
        }

        Set<Throwable> recorded = recordedThrowables;
        if (recorded == null) {
            recorded = Collections.newSetFromMap(new IdentityHashMap<>());
            recordedThrowables = recorded;
        }

        if (!recorded.add(throwable)) {
            return;
        }

        scope.recordException(throwable);
    }

    @Override
    public void close() {
        closed = true;
        try {
            scope.close();
        } finally {
            recordedThrowables = null;
        }
    }
}
