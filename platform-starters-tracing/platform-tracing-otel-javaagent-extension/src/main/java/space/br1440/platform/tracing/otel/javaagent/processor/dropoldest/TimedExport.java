package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.util.Objects;
import jakarta.annotation.Nullable;

import io.opentelemetry.sdk.common.CompletableResultCode;

record TimedExport(boolean success, @Nullable CompletableResultCode unfinished) {

    TimedExport {
        if ((unfinished != null) && success) {
            throw new IllegalArgumentException("A timed-out export cannot be successful");
        }
    }

    static TimedExport finished(boolean success) {
        return new TimedExport(success, null);
    }

    static TimedExport timedOut(CompletableResultCode unfinished) {
        return new TimedExport(false, Objects.requireNonNull(unfinished, "unfinished"));
    }
}
