package space.br1440.platform.tracing.core.exception;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanResult;

import java.util.Objects;

public final class ExceptionRecorder {

    private static final String EXCEPTION_EVENT_NAME = "exception";

    private final ExceptionMessagePolicy messagePolicy;

    public ExceptionRecorder(@Nonnull ExceptionMessagePolicy messagePolicy) {
        this.messagePolicy = Objects.requireNonNull(messagePolicy, "messagePolicy");
    }

    @Nonnull
    public static ExceptionRecorder secureDefault() {
        return new ExceptionRecorder(ExceptionMessagePolicy.secureDefault());
    }

    public void record(@Nullable Throwable t) {
        record(Span.current(), t);
    }

    public void record(@Nullable Span span, @Nullable Throwable exception) {
        if (span == null || exception == null || !span.getSpanContext().isValid()) {
            return;
        }

        String type = exception.getClass().getName();
        span.setAttribute(SemconvKeys.ERROR_TYPE, type);
        span.setAttribute(SemconvKeys.PLATFORM_RESULT, SpanResult.FAILURE.value());

        String safeMessage = messagePolicy.sanitizeOrNull(exception);
        if (safeMessage != null) {
            span.setStatus(StatusCode.ERROR, safeMessage);
        } else {
            span.setStatus(StatusCode.ERROR);
        }

        AttributesBuilder event = Attributes.builder().put(SemconvKeys.EXCEPTION_TYPE, type);
        if (safeMessage != null) {
            event.put(SemconvKeys.EXCEPTION_MESSAGE, safeMessage);
        }

        String stack = messagePolicy.sanitizedStackOrNull(exception);
        if (stack != null) {
            event.put(SemconvKeys.EXCEPTION_STACKTRACE, stack);
        }

        span.addEvent(EXCEPTION_EVENT_NAME, event.build());
    }

    public void markCurrentSpanAsError() {
        Span span = Span.current();
        if (!span.getSpanContext().isValid()) {
            return;
        }

        span.setStatus(StatusCode.ERROR);
        span.setAttribute(SemconvKeys.PLATFORM_RESULT, SpanResult.FAILURE.value());
    }

    public void result(@Nonnull SpanResult result) {
        Objects.requireNonNull(result, "result");

        Span span = Span.current();
        if (span.getSpanContext().isValid()) {
            span.setAttribute(SemconvKeys.PLATFORM_RESULT, result.value());
        }
    }
}
