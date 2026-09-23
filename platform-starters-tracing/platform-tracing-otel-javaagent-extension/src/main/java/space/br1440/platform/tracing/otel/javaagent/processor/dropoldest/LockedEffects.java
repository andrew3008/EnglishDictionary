package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.util.ArrayList;
import java.util.List;

final class LockedEffects {

    private final List<FlushTracker.FlushCompletion> completions = new ArrayList<>(2);
    private int discardedQueued;

    void addDiscardedQueued(int count) {
        discardedQueued += count;
    }

    void addCompletions(List<FlushTracker.FlushCompletion> collected) {
        completions.addAll(collected);
    }

    void publish(DropOldestExportMetrics metrics) {
        try {
            if (discardedQueued != 0) {
                metrics.addDiscardedQueued(discardedQueued);
            }
        } finally {
            FlushTracker.completeAfterUnlock(completions);
        }
    }
}
