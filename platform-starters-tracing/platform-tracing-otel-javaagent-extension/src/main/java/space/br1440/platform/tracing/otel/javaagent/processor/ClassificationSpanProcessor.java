package space.br1440.platform.tracing.otel.javaagent.processor;

import java.time.Duration;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.StatusData;

/**
 * Классифицирует завершающийся span по длительности ({@code fast}/{@code normal}/{@code slow})
 * и проставляет приоритет ({@code high}/{@code normal}) для downstream sampling/routing (tail
 * sampling, приоритетные очереди экспорта).
 * <p>
 * <b>Приоритет не перезаписывается</b>, если уже проставлен upstream-процессором
 * (см. {@link #onEnding} — запись только при {@code getAttribute(PRIORITY_KEY) == null}):
 * этот процессор — fallback-классификатор, а не authoritative источник приоритета.
 */
public final class ClassificationSpanProcessor implements PlatformEndingSpanProcessor, ProcessorDiagnosticIdentity {

    private static final String DIAGNOSTIC_ID = JmxWireContract.KEY_PROCESSOR_ERRORS_BY_NAME_CLASSIFICATION;
    private static final AttributeKey<String> PRIORITY_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_TRACE_PRIORITY);
    private static final AttributeKey<String> DURATION_CLASS_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_TRACE_DURATION_CLASS);

    private final long slowThresholdNanos;
    private final long normalThresholdNanos;

    public ClassificationSpanProcessor(Duration slowThreshold, Duration normalThreshold) {
        this.slowThresholdNanos = slowThreshold.toNanos();
        this.normalThresholdNanos = normalThreshold.toNanos();

        if (slowThresholdNanos <= normalThresholdNanos) {
            throw new IllegalArgumentException("""
                    slowThreshold (%s) must be strictly greater than normalThreshold (%s); \
                    otherwise the 'slow' classification is unreachable"""
                    .formatted(slowThreshold, normalThreshold)
            );
        }
    }

    @Override
    public String diagnosticId() {
        return DIAGNOSTIC_ID;
    }

    @Override
    public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan span) {
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public void onEnding(ReadWriteSpan span) {
        DurationClass durationClass = classifyDuration(span.getLatencyNanos());
        span.setAttribute(DURATION_CLASS_KEY, durationClass.value());

        if (span.getAttribute(PRIORITY_KEY) == null) {
            Priority priority = resolvePriority(durationClass, span);
            span.setAttribute(PRIORITY_KEY, priority.value());
        }
    }

    private DurationClass classifyDuration(long durationNanos) {
        if (durationNanos >= slowThresholdNanos) {
            return DurationClass.SLOW;
        }

        if (durationNanos >= normalThresholdNanos) {
            return DurationClass.NORMAL;
        }

        return DurationClass.FAST;
    }

    private Priority resolvePriority(DurationClass durationClass, ReadWriteSpan span) {
        if (durationClass == DurationClass.SLOW || hasErrorStatus(span)) {
            return Priority.HIGH;
        }

        return Priority.NORMAL;
    }

    private static boolean hasErrorStatus(ReadWriteSpan span) {
        StatusData status = span.toSpanData().getStatus();
        return (status != null) && (status.getStatusCode() == StatusCode.ERROR);
    }

    @Override
    public boolean isOnEndingRequired() {
        return true;
    }

    @Override
    public void onEnd(@Nonnull ReadableSpan span) {
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }

    private enum DurationClass {
        SLOW("slow"),
        NORMAL("normal"),
        FAST("fast");

        private final String value;

        DurationClass(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }

    private enum Priority {
        HIGH("high"),
        NORMAL("normal");

        private final String value;

        Priority(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }
}
