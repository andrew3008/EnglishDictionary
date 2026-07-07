package space.br1440.platform.tracing.otel.extension.processor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.internal.ExtendedSpanProcessor;
import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import java.time.Duration;

public class ClassificationSpanProcessor implements ExtendedSpanProcessor {

    private static final AttributeKey<String> PRIORITY_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_TRACE_PRIORITY);
    private static final AttributeKey<String> DURATION_CLASS_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_TRACE_DURATION_CLASS);

    private final long slowThresholdNanos;
    private final long normalThresholdNanos;

    public ClassificationSpanProcessor(Duration slowThreshold, Duration normalThreshold) {
        this.slowThresholdNanos = slowThreshold.toNanos();
        this.normalThresholdNanos = normalThreshold.toNanos();
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
        long durationNs = span.getLatencyNanos();
        String durationClass;
        boolean isHighPriority = false;

        if (durationNs >= slowThresholdNanos) {
            durationClass = "slow";
            isHighPriority = true;
        } else if (durationNs >= normalThresholdNanos) {
            durationClass = "normal";
        } else {
            durationClass = "fast";
        }

        span.setAttribute(DURATION_CLASS_KEY, durationClass);

        StatusData status = span.toSpanData().getStatus();
        if (status != null && status.getStatusCode() == StatusCode.ERROR) {
            isHighPriority = true;
        }

        if (span.getAttribute(PRIORITY_KEY) == null) {
            span.setAttribute(PRIORITY_KEY, isHighPriority ? "high" : "normal");
        }
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

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }
}
