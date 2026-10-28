package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.util.Objects;

sealed interface WorkerAction permits WorkerAction.ExportBatch, WorkerAction.DelegateHeld, WorkerAction.StopWorker {

    record ExportBatch(SpanQueue.Batch batch) implements WorkerAction {
        public ExportBatch {
            Objects.requireNonNull(batch, "batch");
        }
    }

    record DelegateHeld(long heldNanos) implements WorkerAction {
    }

    record StopWorker(WorkerStopReason reason) implements WorkerAction {
        public StopWorker {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
