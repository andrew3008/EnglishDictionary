package space.br1440.platform.tracing.otel.runtime.otel.scope;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;
import space.br1440.platform.tracing.otel.mdc.SpanContextMdcProjection;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public final class OwningSpanScope implements AutoCloseable {

    private final Span span;
    private final Scope scope;
    private final ExceptionRecorder exceptionRecorder;
    private static final int MAX_RESPONSE_STATUS_CODE_LENGTH = 64;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean resultDeclared = new AtomicBoolean(false);
    private final AtomicBoolean failureLatched = new AtomicBoolean(false);
    private String admittedResponseStatusCode;

    public OwningSpanScope(@Nonnull Span span, @Nonnull Scope scope,
                           @Nonnull ExceptionRecorder exceptionRecorder) {
        this.span = span;
        this.scope = scope;
        this.exceptionRecorder = exceptionRecorder;
    }

    @Nonnull
    public static OwningSpanScope activate(@Nonnull Span span,
                                           @Nonnull ExceptionRecorder exceptionRecorder) {
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(exceptionRecorder, "exceptionRecorder");

        Scope otelScope = Context.current().with(span).makeCurrent();
        try {
            SpanContextMdcProjection.Snapshot previousSpanMdc = SpanContextMdcProjection.installCurrent();
            Scope scope = () -> {
                try {
                    otelScope.close();
                } finally {
                    previousSpanMdc.restore();
                }
            };

            return new OwningSpanScope(span, scope, exceptionRecorder);
        } catch (RuntimeException ex) {
            try {
                otelScope.close();
            } finally {
                span.end();
            }

            throw ex;
        }
    }

    @Nonnull
    public OwningSpanScope setAttribute(@Nonnull String key, @Nullable String value) {
        if (value != null) {
            span.setAttribute(key, value);
        }

        return this;
    }

    @Nonnull
    public OwningSpanScope setAttribute(@Nonnull String key, long value) {
        span.setAttribute(key, value);
        return this;
    }

    @Nonnull
    public OwningSpanScope setAttribute(@Nonnull String key, double value) {
        span.setAttribute(key, value);
        return this;
    }

    @Nonnull
    public OwningSpanScope setAttribute(@Nonnull String key, boolean value) {
        span.setAttribute(key, value);
        return this;
    }

    @Nonnull
    public OwningSpanScope setResult(@Nonnull SpanResult result) {
        applyResult(result);
        resultDeclared.set(true);
        return this;
    }

    public void responseStatusCode(@Nullable String code) {
        if (closed.get() || !span.getSpanContext().isValid()) {
            return;
        }

        String admitted = admitResponseStatusCode(code);
        if (admitted == null) {
            return;
        }

        admittedResponseStatusCode = admitted;
        span.setAttribute(SemconvKeys.DB_RESPONSE_STATUS_CODE, admitted);
        if (failureLatched.get()) {
            span.setAttribute(SemconvKeys.ERROR_TYPE, admitted);
        }
    }

    @Nonnull
    public OwningSpanScope recordException(@Nullable Throwable throwable) {
        exceptionRecorder.record(span, throwable, admittedResponseStatusCode);
        if (throwable != null) {
            failureLatched.set(true);
            resultDeclared.set(true);
        }

        return this;
    }

    @Nullable
    private static String admitResponseStatusCode(@Nullable String code) {
        if (code == null || code.isBlank() || code.length() > MAX_RESPONSE_STATUS_CODE_LENGTH) {
            return null;
        }

        for (int index = 0; index < code.length(); index++) {
            char character = code.charAt(index);
            boolean letter = (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z');
            boolean digit = character >= '0' && character <= '9';
            if (!letter && !digit && character != '-' && character != '/') {
                return null;
            }
        }

        return code;
    }

    private void applyResult(@Nonnull SpanResult result) {
        if (result == SpanResult.FAILURE) {
            failureLatched.set(true);
        } else if (failureLatched.get()) {
            return;
        }

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

        if (!resultDeclared.get()) {
            try {
                applyResult(SpanResult.SUCCESS);
            } catch (RuntimeException e) {
                log.warn("Failed to apply default result for span '{}': {}", span, e.getMessage());
            }
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
