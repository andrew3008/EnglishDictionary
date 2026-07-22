package space.br1440.platform.tracing.core.runtime;

import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;

public final class NoOpSpanHandle implements SpanHandle {

    public static final NoOpSpanHandle INSTANCE = new NoOpSpanHandle();

    private NoOpSpanHandle() {
    }

    @Override
    public void recordException(@Nullable Throwable throwable) {
    }

    @Override
    public void close() {
    }
}
