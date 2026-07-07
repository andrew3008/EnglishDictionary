package space.br1440.platform.tracing.core.span;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.api.span.SpanScope;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;
import space.br1440.platform.tracing.core.semconv.ValidatedAttributes;

/**
 * {@link SpanScope}, возвращаемый когда builder ДЕГРАДИРОВАЛ в enrich-current (re-entry платформы
 * той же категории, модель B). НЕ владеет жизненным циклом span'а: {@link #close()} — no-op
 * (не {@code span.end()}, не закрытие чужого {@code Scope}).
 * <p>
 * Это отдельный ТИП (а не просто тест), чтобы будущий рефакторинг случайно не вернул владеющий
 * {@link OwningSpanScope} и не завершил родительский span на {@code close()}.
 * <p>
 * Мутирующие методы применяются к текущему (чужому, родительскому) span'у — это и есть enrich;
 * но завершение span'а остаётся за тем, кто его создал.
 */
final class NonOwningSpanScope implements SpanScope {

    private final Span span;
    private final ExceptionRecorder exceptionRecorder;

    private NonOwningSpanScope(Span span, ExceptionRecorder exceptionRecorder) {
        this.span = span;
        this.exceptionRecorder = exceptionRecorder;
    }

    /**
     * Применяет нормализованные атрибуты к текущему span'у (если он пишется) и возвращает
     * non-owning scope.
     */
    @Nonnull
    static SpanScope enrich(@Nonnull Span span,
                            @Nonnull Attributes accumulated,
                            @Nonnull AttributePolicy policy,
                            @Nonnull SpanCategory category,
                            @Nonnull String builderName,
                            @Nonnull ExceptionRecorder exceptionRecorder) {
        ValidatedAttributes validated = policy.validateAndNormalize(category, accumulated, builderName);
        if (span.isRecording()) {
            span.setAllAttributes(validated.attributes());
        }
        return new NonOwningSpanScope(span, exceptionRecorder);
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
        span.setAttribute(PlatformAttributes.PLATFORM_RESULT, result.value());
        if (result == SpanResult.FAILURE) {
            span.setStatus(StatusCode.ERROR);
        }
        return this;
    }

    @Override
    @Nonnull
    public SpanScope recordException(@Nullable Throwable throwable) {
        // Через ExceptionRecorder: санитизированный exception-event + error.type +
        // StatusCode.ERROR + platform.trace.result=failure на чужом (родительском) span'е.
        exceptionRecorder.record(span, throwable);
        return this;
    }

    @Override
    public void close() {
        // no-op: span не наш (re-entry платформы) — span.end()/scope.close() НЕ вызываем.
    }
}
