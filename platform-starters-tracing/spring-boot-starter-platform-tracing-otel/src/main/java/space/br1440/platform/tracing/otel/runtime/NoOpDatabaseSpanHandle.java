package space.br1440.platform.tracing.otel.runtime;

import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.span.spec.DatabaseSpanHandle;

public final class NoOpDatabaseSpanHandle implements DatabaseSpanHandle {

    public static final NoOpDatabaseSpanHandle INSTANCE = new NoOpDatabaseSpanHandle();

    private NoOpDatabaseSpanHandle() {
    }

    @Override
    public void responseStatusCode(@Nullable String code) {
    }

    @Override
    public void recordException(@Nullable Throwable throwable) {
    }

    @Override
    public void close() {
    }
}
