package space.br1440.platform.tracing.core.runtime;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.span.SpanScope;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * {@link SpanHandle} backed by {@link SpanScope} with exactly-once exception recording per
 * {@link Throwable} instance (Slice 4).
 */
public final class SpanHandleImpl implements SpanHandle {

    private final SpanScope scope;
    private final Set<Throwable> recordedThrowables =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private SpanHandleImpl(@Nonnull SpanScope scope) {
        this.scope = scope;
    }

    @Nonnull
    public static SpanHandle wrap(@Nonnull SpanScope scope) {
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
