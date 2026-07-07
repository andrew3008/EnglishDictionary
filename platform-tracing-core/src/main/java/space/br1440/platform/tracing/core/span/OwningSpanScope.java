package space.br1440.platform.tracing.core.span;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.api.span.SpanScope;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link SpanScope}, ВЛАДЕЮЩИЙ жизненным циклом span'а: {@link #close()} закрывает {@link Scope}
 * и завершает span ({@code span.end()}).
 * <p>
 * Используется и процедурным {@code DefaultPlatformTracing}, и типизированными builder'ами при
 * создании нового span'а (в отличие от {@link NonOwningSpanScope}, который возвращается при
 * re-entry-деградации в enrich и НЕ завершает чужой span).
 * <p>
 * {@code close()} идемпотентен и потоко-безопасен (Фаза 11): ровно один поток выполняет
 * завершение, повторные вызовы — no-op. Ошибки tracing-слоя при закрытии подавляются (логируются),
 * чтобы не маскировать бизнес-исключение в неявном finally try-with-resources.
 */
public final class OwningSpanScope implements SpanScope {

    private static final Logger log = LoggerFactory.getLogger(OwningSpanScope.class);

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
        // Через ExceptionRecorder: exception-event санитизируется (events не скрабятся
        // ScrubbingSpanProcessor'ом). Recorder сам проставляет error.type, StatusCode.ERROR
        // и platform.trace.result=failure.
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
        // Порядок (scope.close -> span.end) соответствует идиоме OTel try-with-resources.
        try {
            scope.close();
        } catch (RuntimeException e) {
            log.warn("Не удалось закрыть Scope для span '{}': {}", span, e.getMessage());
        }
        try {
            span.end();
        } catch (RuntimeException e) {
            log.warn("Не удалось завершить span '{}': {}", span, e.getMessage());
        }
    }
}
