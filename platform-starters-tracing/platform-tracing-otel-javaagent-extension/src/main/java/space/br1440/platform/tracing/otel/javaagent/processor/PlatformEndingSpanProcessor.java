package space.br1440.platform.tracing.otel.javaagent.processor;

import jakarta.annotation.Nonnull;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.internal.ExtendedSpanProcessor;

public interface PlatformEndingSpanProcessor extends ExtendedSpanProcessor {

    static PlatformEndingSpanProcessor adapt(SpanProcessor processor) {
        if (processor instanceof PlatformEndingSpanProcessor platformEnding) {
            return platformEnding;
        }

        if (processor instanceof ExtendedSpanProcessor externalEnding) {
            return new ExternalExtendedSpanProcessorAdapter(processor, externalEnding);
        }

        return null;
    }
}

final class ExternalExtendedSpanProcessorAdapter implements PlatformEndingSpanProcessor {

    private final SpanProcessor delegate;
    private final ExtendedSpanProcessor endingDelegate;

    ExternalExtendedSpanProcessorAdapter(SpanProcessor delegate, ExtendedSpanProcessor endingDelegate) {
        this.delegate = delegate;
        this.endingDelegate = endingDelegate;
    }

    @Override
    public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan span) {
        delegate.onStart(parentContext, span);
    }

    @Override
    public boolean isStartRequired() {
        return delegate.isStartRequired();
    }

    @Override
    public void onEnding(ReadWriteSpan span) {
        endingDelegate.onEnding(span);
    }

    @Override
    public boolean isOnEndingRequired() {
        return endingDelegate.isOnEndingRequired();
    }

    @Override
    public void onEnd(@Nonnull ReadableSpan span) {
        delegate.onEnd(span);
    }

    @Override
    public boolean isEndRequired() {
        return delegate.isEndRequired();
    }

    @Override
    public CompletableResultCode forceFlush() {
        return delegate.forceFlush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }
}
