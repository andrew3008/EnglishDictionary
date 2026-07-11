package space.br1440.platform.tracing.core.runtime.otel.scope;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.api.span.SpanScope;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public final class OwningSpanScope implements SpanScope {

    private final Span span;
    private final Scope scope;
    private final ExceptionRecorder exceptionRecorder;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public OwningSpanScope(@Nonnull Span span, @Nonnull Scope scope,
                           @Nonnull ExceptionRecorder exceptionRecorder) {
        this.span = span;
        this.scope = scope;
        this.exceptionRecorder = exceptionRecorder;
    }

    @Override
    @Nonnull
    public SpanScope setAttribute(@Nonnull String key, @Nullable String value) {
        if (value != null) {
            span.setAttribute(key, value);
        }

        return this;
    }

    @Override
    @Nonnull
    public SpanScope setAttribute(@Nonnull String key, long value) {
        span.setAttribute(key, value);
        return this;
    }

    @Override
    @Nonnull
    public SpanScope setAttribute(@Nonnull String key, double value) {
        span.setAttribute(key, value);
        return this;
    }

    @Override
    @Nonnull
    public SpanScope setAttribute(@Nonnull String key, boolean value) {
        span.setAttribute(key, value);
        return this;
    }

    @Override
    @Nonnull
    public SpanScope addEvent(@Nonnull String name) {
        span.addEvent(name);
        return this;
    }

    @Override
    @Nonnull
    public SpanScope setResult(@Nonnull SpanResult result) {
        applyResult(result);
        return this;
    }

    @Override
    @Nonnull
    public SpanScope recordException(@Nullable Throwable throwable) {
        exceptionRecorder.record(span, throwable);
        return this;
    }

    private void applyResult(@Nonnull SpanResult result) {
        span.setAttribute(PlatformAttributes.PLATFORM_RESULT, result.value());
        if (result == SpanResult.FAILURE) {
            span.setStatus(StatusCode.ERROR);
        } else {
            span.setStatus(StatusCode.OK);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        try {
            scope.close();
        } catch (RuntimeException e) {
            log.warn("Failed to close Scope for span '{}': {}", span, e.getMessage());
        }

        try {
            span.end();
        } catch (RuntimeException e) {
            log.warn("Failed to end span '{}': {}", span, e.getMessage());
        }
    }
}
