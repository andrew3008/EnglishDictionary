package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.LongPredicate;

import io.opentelemetry.sdk.common.CompletableResultCode;

final class FlushTracker {

    private Generation active;
    private Generation queued;

    boolean hasActive() {
        return active != null;
    }

    int pendingGenerationCount() {
        return (active == null ? 0 : 1) + (queued == null ? 0 : 1);
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

    void markFailureAtOrBefore(long sequence) {
        markIfCovers(active, sequence);
        markIfCovers(queued, sequence);
    }

    void markFailureAtOrBefore(Collection<SequencedSpan> spans) {
        if (spans.isEmpty()) {
            return;
        }

        markFailureAtOrBefore(spans.iterator().next().sequence());
    }

    void failAllPending() {
        if (active != null) {
            active.failed = true;
        }

        if (queued != null) {
            queued.failed = true;
        }
    }

    void markTimedOutFailureIfOverlapping(Long inFlightFirstSequence) {
        markIfOverlapping(active, inFlightFirstSequence);
        markIfOverlapping(queued, inFlightFirstSequence);
    }

    List<FlushCompletion> collectTerminal(LongPredicate isResolvedUpTo, Long inFlightFirstSequence) {
        List<FlushCompletion> completions = new ArrayList<>(2);
        while (active != null) {
            boolean resolved = isResolvedUpTo.test(active.watermark);
            if (!active.failed && !resolved) {
                break;
            }

            completions.add(new FlushCompletion(active.result, !active.failed));
            active = queued;
            queued = null;
            markIfOverlapping(active, inFlightFirstSequence);
        }

        return completions;
    }

    static void completeAfterUnlock(List<FlushCompletion> completions) {
        for (FlushCompletion completion : completions) {
            if (completion.success()) {
                completion.result().succeed();
            } else {
                completion.result().fail();
            }
        }
    }

    private static void markIfCovers(Generation generation, long sequence) {
        if (generation != null && sequence <= generation.watermark) {
            generation.failed = true;
        }
    }

    private static void markIfOverlapping(Generation generation, Long inFlightFirstSequence) {
        if (generation == null || inFlightFirstSequence == null) {
            return;
        }

        if (inFlightFirstSequence <= generation.watermark) {
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
