package space.br1440.platform.tracing.otel.javaagent.processor.composite;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.otel.javaagent.logging.RateLimitedLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ExportPipelineLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.processor.PlatformEndingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

public final class PlatformCompositeSpanProcessor implements PlatformEndingSpanProcessor {

    private static final RateLimitedLogger log = new RateLimitedLogger(PlatformCompositeSpanProcessor.class);

    private final List<NamedDelegate> delegates;
    private final NamedDelegate[] startDelegates;
    private final NamedDelegate[] endDelegates;
    private final NamedEndingDelegate[] onEndingDelegates;
    private final CompositeLifecycleCoordinator lifecycle;

    public PlatformCompositeSpanProcessor(List<? extends SpanProcessor> delegates) {
        Objects.requireNonNull(delegates, "delegates");

        DelegateWiring wiring = DelegateWiring.wire(delegates);
        this.delegates = wiring.delegates();
        this.startDelegates = wiring.startDelegates();
        this.endDelegates = wiring.endDelegates();
        this.onEndingDelegates = wiring.onEndingDelegates();
        this.lifecycle = new CompositeLifecycleCoordinator(this.delegates);
    }

    @Override
    @SuppressWarnings("resource")
    public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan span) {
        for (NamedDelegate namedDelegate : startDelegates) {
            try {
                namedDelegate.delegate().onStart(parentContext, span);
            } catch (Throwable failure) {
                recordCallbackFailure(namedDelegate, failure, "onStart");
            }
        }
    }

    @Override
    public boolean isStartRequired() {
        return (startDelegates.length > 0);
    }

    @Override
    @SuppressWarnings("resource")
    public void onEnding(ReadWriteSpan span) {
        for (NamedEndingDelegate namedEndingDelegate : onEndingDelegates) {
            try {
                namedEndingDelegate.endingDelegate().onEnding(span);
            } catch (Throwable failure) {
                recordCallbackFailure(namedEndingDelegate.namedDelegate(), failure, "onEnding");
            }
        }
    }

    @Override
    public boolean isOnEndingRequired() {
        return (onEndingDelegates.length > 0);
    }

    @Override
    @SuppressWarnings("resource")
    public void onEnd(@Nonnull ReadableSpan span) {
        for (NamedDelegate namedDelegate : endDelegates) {
            try {
                namedDelegate.delegate().onEnd(span);
            } catch (Throwable failure) {
                recordCallbackFailure(namedDelegate, failure, "onEnd");
            }
        }
    }

    @Override
    public boolean isEndRequired() {
        return (endDelegates.length > 0);
    }

    @Override
    public CompletableResultCode shutdown() {
        return lifecycle.shutdown();
    }

    @Override
    public CompletableResultCode forceFlush() {
        return lifecycle.forceFlush();
    }

    public Map<String, Long> getProcessorErrorCounts() {
        Map<String, Long> snapshot = new LinkedHashMap<>(delegates.size());
        for (NamedDelegate namedDelegate : delegates) {
            snapshot.put(namedDelegate.name(), namedDelegate.errorCount().get());
        }

        return Collections.unmodifiableMap(snapshot);
    }

    private void recordCallbackFailure(NamedDelegate namedDelegate, Throwable failure, String phase) {
        PlatformThrowables.propagateIfFatal(failure);
        namedDelegate.errorCount().incrementAndGet();
        log.log(ExportPipelineLogEvent.COMPOSITE_PROCESSOR_CALLBACK_FAILED, fields -> fields
                .putString(AgentLogField.PHASE, phase)
                .putString(AgentLogField.DELEGATE, namedDelegate.name())
                .cause(failure));
    }
}
