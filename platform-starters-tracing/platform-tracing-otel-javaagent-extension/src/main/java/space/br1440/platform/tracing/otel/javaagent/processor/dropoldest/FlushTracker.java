package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

import space.br1440.platform.tracing.otel.javaagent.logging.RateLimitedLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ExportPipelineLogEvent;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

import io.opentelemetry.sdk.common.CompletableResultCode;

final class FlushTracker {

    private static final RateLimitedLogger log = new RateLimitedLogger(FlushTracker.class);

    private Generation active;
    private Generation queued;

    boolean hasActive() {
        return active != null;
    }

    CompletableResultCode requestOrExtend(long watermark) {
        if (active == null) {
            active = new Generation(watermark);
            return active.result;
        }

        if (queued == null) {
            queued = new Generation(watermark);
        } else {
            queued.extendTo(watermark);
        }

        return queued.result;
    }

    void failGenerationsCovering(long sequence) {
        markIfCovers(active, sequence);
        markIfCovers(queued, sequence);
    }

    void failAllPending() {
        if (active != null) {
            active.failed = true;
        }

        if (queued != null) {
            queued.failed = true;
        }
    }

    List<FlushCompletion> collectTerminal(LongPredicate isResolvedUpTo) {
        List<FlushCompletion> completions = new ArrayList<>(2);
        while (active != null) {
            boolean resolved = isResolvedUpTo.test(active.watermark);
            if (!active.failed && !resolved) {
                break;
            }

            completions.add(new FlushCompletion(active.result, !active.failed));
            active = queued;
            queued = null;
        }

        return completions;
    }

    static void completeAfterUnlock(List<FlushCompletion> completions) {
        List<Throwable> callbackFailures = null;
        for (FlushCompletion completion : completions) {
            try {
                if (completion.success()) {
                    completion.result().succeed();
                } else {
                    completion.result().fail();
                }
            } catch (Throwable callbackFailure) {
                if (callbackFailures == null) {
                    callbackFailures = new ArrayList<>(2);
                }
                callbackFailures.add(callbackFailure);
            }
        }

        reportCallbackFailures(callbackFailures);
    }

    private static void reportCallbackFailures(List<Throwable> callbackFailures) {
        if (callbackFailures == null) {
            return;
        }

        for (Throwable callbackFailure : callbackFailures) {
            PlatformThrowables.propagateIfFatal(callbackFailure);
        }

        Throwable primary = callbackFailures.getFirst();
        for (int index = 1; index < callbackFailures.size(); index++) {
            Throwable secondary = callbackFailures.get(index);
            if (secondary != primary) {
                primary.addSuppressed(secondary);
            }
        }
        log.log(ExportPipelineLogEvent.FLUSH_COMPLETION_CALLBACK_FAILED, fields -> fields.cause(primary));
    }

    private static void markIfCovers(Generation generation, long sequence) {
        if (generation != null && sequence <= generation.watermark) {
            generation.failed = true;
        }
    }

    private static final class Generation {
        private final CompletableResultCode result = new CompletableResultCode();
        private long watermark;
        private boolean failed;

        private Generation(long watermark) {
            this.watermark = watermark;
        }

        private void extendTo(long requestedWatermark) {
            watermark = Math.max(watermark, requestedWatermark);
        }
    }

    record FlushCompletion(CompletableResultCode result, boolean success) {
    }
}
