package space.br1440.platform.tracing.otel.javaagent.processor;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.internal.ExtendedSpanProcessor;
import jakarta.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.tracing.otel.javaagent.exception.TracingValidationException;
import space.br1440.platform.tracing.otel.javaagent.safety.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.safety.RateLimitedLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public final class PlatformCompositeSpanProcessor implements ExtendedSpanProcessor {

    private final RateLimitedLogger rateLimitedLog = new RateLimitedLogger(log);

    private final List<NamedDelegate> delegates;
    private final Map<String, AtomicLong> errorCounts;
    private final boolean anyStartRequired;
    private final boolean anyEndRequired;
    private final boolean anyOnEndingRequired;

    public PlatformCompositeSpanProcessor(List<? extends SpanProcessor> delegates) {
        Objects.requireNonNull(delegates, "delegates");

        List<NamedDelegate> wrapped = new ArrayList<>(delegates.size());
        Map<String, AtomicLong> counters = new LinkedHashMap<>();
        boolean startReq = false;
        boolean endReq = false;
        boolean endingReq = false;

        for (SpanProcessor delegate : delegates) {
            Objects.requireNonNull(delegate, "delegate");

            String name = resolveDelegateName(delegate);
            wrapped.add(new NamedDelegate(name, delegate));
            counters.computeIfAbsent(name, k -> new AtomicLong());

            startReq |= delegate.isStartRequired();
            endReq |= delegate.isEndRequired();
            if (delegate instanceof ExtendedSpanProcessor extended && extended.isOnEndingRequired()) {
                endingReq = true;
            }
        }

        this.delegates = List.copyOf(wrapped);
        this.errorCounts = Collections.unmodifiableMap(counters);
        this.anyStartRequired = startReq;
        this.anyEndRequired = endReq;
        this.anyOnEndingRequired = endingReq;
    }

    @Override
    public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan span) {
        for (NamedDelegate nd : delegates) {
            if (!nd.delegate.isStartRequired()) {
                continue;
            }

            try {
                nd.delegate.onStart(parentContext, span);
            } catch (Throwable e) {
                PlatformThrowables.propagateIfFatal(e);
                recordError(nd.name);
                rateLimitedLog.warn("Error in onStart of '{}' for span '{}': {}", nd.name, span.getName(), e.getMessage());
            }
        }
    }

    @Override
    public boolean isStartRequired() {
        return anyStartRequired;
    }

    @Override
    public void onEnding(ReadWriteSpan span) {
        for (NamedDelegate nd : delegates) {
            if (!(nd.delegate instanceof ExtendedSpanProcessor extended) || !extended.isOnEndingRequired()) {
                continue;
            }

            try {
                extended.onEnding(span);
            } catch (TracingValidationException e) {
                throw e;
            } catch (Throwable e) {
                PlatformThrowables.propagateIfFatal(e);
                recordError(nd.name);
                rateLimitedLog.warn("Error in onEnding of '{}' for span '{}': {}", nd.name, span.getName(), e.getMessage());
            }
        }
    }

    @Override
    public boolean isOnEndingRequired() {
        return anyOnEndingRequired;
    }

    @Override
    public void onEnd(@Nonnull ReadableSpan span) {
        for (NamedDelegate nd : delegates) {
            if (!nd.delegate.isEndRequired()) {
                continue;
            }

            try {
                nd.delegate.onEnd(span);
            } catch (Throwable e) {
                PlatformThrowables.propagateIfFatal(e);
                recordError(nd.name);
                rateLimitedLog.warn("Error in onEnd of '{}' for span '{}': {}", nd.name, span.getName(), e.getMessage());
            }
        }
    }

    @Override
    public boolean isEndRequired() {
        return anyEndRequired;
    }

    @Override
    public CompletableResultCode shutdown() {
        List<CompletableResultCode> codes = new ArrayList<>(delegates.size());
        for (NamedDelegate nd : delegates) {
            try {
                codes.add(nd.delegate.shutdown());
            } catch (Throwable e) {
                PlatformThrowables.propagateIfFatal(e);
                recordError(nd.name);
                log.warn("Error in shutdown of '{}': {}", nd.name, e.getMessage());

                codes.add(CompletableResultCode.ofFailure());
            }
        }

        return CompletableResultCode.ofAll(codes);
    }

    @Override
    public CompletableResultCode forceFlush() {
        List<CompletableResultCode> codes = new ArrayList<>(delegates.size());
        for (NamedDelegate nd : delegates) {
            try {
                codes.add(nd.delegate.forceFlush());
            } catch (Throwable e) {
                PlatformThrowables.propagateIfFatal(e);
                recordError(nd.name);
                log.warn("Error in forceFlush of '{}': {}", nd.name, e.getMessage());

                codes.add(CompletableResultCode.ofFailure());
            }
        }

        return CompletableResultCode.ofAll(codes);
    }

    @Override
    public void close() {
        for (NamedDelegate nd : delegates) {
            try {
                nd.delegate.close();
            } catch (Throwable e) {
                PlatformThrowables.propagateIfFatal(e);
                recordError(nd.name);
                log.warn("Error in close of '{}': {}", nd.name, e.getMessage());
            }
        }
    }

    public Map<String, Long> getProcessorErrorCounts() {
        Map<String, Long> snapshot = new LinkedHashMap<>(errorCounts.size());
        for (Map.Entry<String, AtomicLong> entry : errorCounts.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().get());
        }

        return Collections.unmodifiableMap(snapshot);
    }

    private void recordError(String delegateName) {
        AtomicLong counter = errorCounts.get(delegateName);
        if (counter != null) {
            counter.incrementAndGet();
        }
    }

    private static String resolveDelegateName(SpanProcessor delegate) {
        String simple = delegate.getClass().getSimpleName();
        return simple.isEmpty() ? delegate.getClass().getName() : simple;
    }

    private record NamedDelegate(String name, SpanProcessor delegate) {
    }
}
