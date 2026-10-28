package space.br1440.platform.tracing.otel.runtime;

import java.util.Objects;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.span.spec.DatabaseSpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.otel.runtime.otel.scope.OwningSpanScope;

public final class DatabaseSpanHandleImpl implements DatabaseSpanHandle {

    private final SpanHandle delegate;
    private final OwningSpanScope scope;

    private DatabaseSpanHandleImpl(@Nonnull SpanHandle delegate, @Nonnull OwningSpanScope scope) {
        this.delegate = delegate;
        this.scope = scope;
    }

    @Nonnull
    public static DatabaseSpanHandle wrap(@Nonnull OwningSpanScope scope) {
        Objects.requireNonNull(scope, "scope");
        return new DatabaseSpanHandleImpl(SpanHandleImpl.wrap(scope), scope);
    }

    @Override
    public void responseStatusCode(@Nullable String code) {
        scope.responseStatusCode(code);
    }

    @Override
    public void recordException(@Nullable Throwable throwable) {
        delegate.recordException(throwable);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
