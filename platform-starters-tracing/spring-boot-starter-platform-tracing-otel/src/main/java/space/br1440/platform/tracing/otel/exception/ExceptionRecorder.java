package space.br1440.platform.tracing.otel.exception;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanResult;

import java.util.Objects;

public final class ExceptionRecorder {

    private static final String EXCEPTION_EVENT_NAME = "exception";

    private final ExceptionRecordingPolicy recordingPolicy;

    public ExceptionRecorder(@Nonnull ExceptionRecordingPolicy recordingPolicy) {
        this.recordingPolicy = Objects.requireNonNull(recordingPolicy, "recordingPolicy");
    }

    @Nonnull
    public static ExceptionRecorder secureDefault() {
        return new ExceptionRecorder(ExceptionRecordingPolicy.secureDefault());
    }

    public void record(@Nullable Throwable t) {
        record(Span.current(), t);
    }

    public void record(@Nullable Span span, @Nullable Throwable exception) {
        record(span, exception, null);
    }

    public void record(@Nullable Span span, @Nullable Throwable exception, @Nullable String errorType) {
        if (span == null || exception == null || !span.getSpanContext().isValid()) {
            return;
        }

        String type = exception.getClass().getName();
        span.setAttribute(SemconvKeys.ERROR_TYPE, errorTypeOrExceptionClass(errorType, type));
        span.setAttribute(SemconvKeys.PLATFORM_RESULT, SpanResult.FAILURE.value());

        String safeMessage = recordingPolicy.sanitizeOrNull(exception);
        if (safeMessage != null) {
            span.setStatus(StatusCode.ERROR, safeMessage);
        } else {
            span.setStatus(StatusCode.ERROR);
        }

        AttributesBuilder event = Attributes.builder().put(SemconvKeys.EXCEPTION_TYPE, type);
        if (safeMessage != null) {
            event.put(SemconvKeys.EXCEPTION_MESSAGE, safeMessage);
        }

        String stack = recordingPolicy.sanitizedStackOrNull(exception);
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

    @Nonnull
    private static String errorTypeOrExceptionClass(@Nullable String errorType, @Nonnull String exceptionType) {
        if (errorType == null || errorType.isBlank()) {
            return exceptionType;
        }

        return errorType;
    }
}
