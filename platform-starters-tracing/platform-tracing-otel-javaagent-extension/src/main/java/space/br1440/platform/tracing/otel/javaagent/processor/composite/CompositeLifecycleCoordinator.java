package space.br1440.platform.tracing.otel.javaagent.processor.composite;

import java.util.ArrayList;
import java.util.List;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SpanProcessor;

final class CompositeLifecycleCoordinator {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(CompositeLifecycleCoordinator.class);

    private final List<NamedDelegate> delegates;
    private final Object monitor = new Object();
    private final List<CompletableResultCode> inFlightFlushes = new ArrayList<>();

    private CompletableResultCode sharedShutdownResult;

    CompositeLifecycleCoordinator(List<NamedDelegate> delegates) {
        this.delegates = delegates;
    }

    CompletableResultCode forceFlush() {
        CompletableResultCode flushResult;
        synchronized (monitor) {
            if (sharedShutdownResult != null) {
                return sharedShutdownResult;
            }

            flushResult = new CompletableResultCode();
            inFlightFlushes.add(flushResult);
        }

        flushResult.whenComplete(() -> unregisterFlush(flushResult));
        try {
            CompletableResultCode delegateResults = invokeDelegates(Operation.FORCE_FLUSH, flushResult);
            completeFrom(delegateResults, flushResult);
        } catch (VirtualMachineError | LinkageError fatal) {
            flushResult.fail();
            throw fatal;
        }

        return flushResult;
    }

    CompletableResultCode shutdown() {
        CompletableResultCode shutdownResult;
        List<CompletableResultCode> precedingFlushes;
        synchronized (monitor) {
            if (sharedShutdownResult != null) {
                return sharedShutdownResult;
            }

            shutdownResult = new CompletableResultCode();
            sharedShutdownResult = shutdownResult;
            precedingFlushes = List.copyOf(inFlightFlushes);
        }

        CompletableResultCode precedingFlushResult = CompletableResultCode.ofAll(precedingFlushes);
        precedingFlushResult.whenComplete(() -> completeShutdown(shutdownResult, precedingFlushResult));
        return shutdownResult;
    }

    private void unregisterFlush(CompletableResultCode flushResult) {
        synchronized (monitor) {
            inFlightFlushes.remove(flushResult);
        }
    }

    private void completeShutdown(CompletableResultCode shutdownResult, CompletableResultCode precedingFlushResult) {
        try {
            CompletableResultCode delegateResults = invokeDelegates(Operation.SHUTDOWN, shutdownResult);
            completeFrom(CompletableResultCode.ofAll(List.of(precedingFlushResult, delegateResults)), shutdownResult);
        } catch (VirtualMachineError | LinkageError fatal) {
            shutdownResult.fail();
            throw fatal;
        }
    }

    private CompletableResultCode invokeDelegates(Operation operation, CompletableResultCode operationResult) {
        List<CompletableResultCode> results = new ArrayList<>(delegates.size());
        for (NamedDelegate namedDelegate : delegates) {
            try {
                CompletableResultCode delegateResult = operation.invoke(namedDelegate.delegate());
                results.add(observeDelegateResult(namedDelegate, operation, delegateResult, operationResult));
            } catch (Throwable failure) {
                PlatformThrowables.propagateIfFatal(failure);
                recordError(namedDelegate, operation, "threw an exception");
                results.add(CompletableResultCode.ofFailure());
            }
        }

        return CompletableResultCode.ofAll(results);
    }

    private CompletableResultCode observeDelegateResult(NamedDelegate namedDelegate,
                                                        Operation operation,
                                                        CompletableResultCode delegateResult,
                                                        CompletableResultCode operationResult) {
        if (delegateResult == null) {
            recordError(namedDelegate, operation, "returned null");
            return CompletableResultCode.ofFailure();
        }

        if ((delegateResult == operationResult) || (delegateResult == publishedShutdownResult())) {
            recordError(namedDelegate, operation, "returned the composite lifecycle result");
            return CompletableResultCode.ofFailure();
        }

        CompletableResultCode observedResult = new CompletableResultCode();
        delegateResult.whenComplete(() -> {
            if (delegateResult.isSuccess()) {
                observedResult.succeed();
            } else {
                recordError(namedDelegate, operation, "completed with failure");
                observedResult.fail();
            }
        });

        return observedResult;
    }

    private CompletableResultCode publishedShutdownResult() {
        synchronized (monitor) {
            return sharedShutdownResult;
        }
    }

    private static void recordError(NamedDelegate namedDelegate, Operation operation, String failureKind) {
        namedDelegate.errorCount().incrementAndGet();
        log.warn(AgentEventCodes.EXPORT_COMPOSITE_LIFECYCLE, "Composite processor lifecycle failed",
                "phase", operation.displayName,
                "delegate", namedDelegate.name(),
                "kind", failureKind);
    }

    private static void completeFrom(CompletableResultCode source, CompletableResultCode target) {
        source.whenComplete(() -> {
            if (source.isSuccess()) {
                target.succeed();
            } else {
                target.fail();
            }
        });
    }

    private enum Operation {
        FORCE_FLUSH("forceFlush") {
            @Override
            CompletableResultCode invoke(SpanProcessor delegate) {
                return delegate.forceFlush();
            }
        },

        SHUTDOWN("shutdown") {
            @Override
            CompletableResultCode invoke(SpanProcessor delegate) {
                return delegate.shutdown();
            }
        };

        private final String displayName;

        Operation(String displayName) {
            this.displayName = displayName;
        }

        abstract CompletableResultCode invoke(SpanProcessor delegate);
    }
}
