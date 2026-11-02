package space.br1440.platform.tracing.otel.exception;

import java.util.Objects;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

public final class ExceptionRecorder {

    private static final Logger log = LoggerFactory.getLogger(ExceptionRecorder.class);
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
        if (span == null || exception == null || !span.isRecording()) {
            return;
        }

        String type = exception.getClass().getName();
        span.setAttribute(SemconvKeys.ERROR_TYPE, errorTypeOrExceptionClass(errorType, type));
        span.setAttribute(SemconvKeys.PLATFORM_RESULT, SpanResult.FAILURE.value());
        span.setStatus(StatusCode.ERROR);

        AttributesBuilder event = Attributes.builder().put(SemconvKeys.EXCEPTION_TYPE, type);
        try {
            String safeMessage = recordingPolicy.sanitizeOrNull(exception);
            if (safeMessage != null) {
                event.put(SemconvKeys.EXCEPTION_MESSAGE, safeMessage);
                span.setStatus(StatusCode.ERROR, safeMessage);
            }

            String stack = recordingPolicy.renderStack(exception, safeMessage);
            if ((stack != null) && !stack.isEmpty()) {
                event.put(SemconvKeys.EXCEPTION_STACKTRACE, stack);
            }
        } catch (RuntimeException failure) {
            log.debug("Failed to capture exception details: {}", failure.getClass().getName());
        }

        span.addEvent(EXCEPTION_EVENT_NAME, event.build());
    }

    @Nonnull
    private static String errorTypeOrExceptionClass(@Nullable String errorType, @Nonnull String exceptionType) {
        if (errorType == null || errorType.isBlank()) {
            return exceptionType;
        }

        return errorType;
    }
}
