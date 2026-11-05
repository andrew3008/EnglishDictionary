package space.br1440.platform.tracing.autoconfigure.integrations.errorhandling;

import java.util.Objects;

import org.springframework.lang.NonNull;

import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.mvc.observability.ExceptionObservabilityHook;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;

import io.opentelemetry.api.trace.Span;

final class ResolvedServerErrorEventHook implements ExceptionObservabilityHook {

    private final ExceptionRecorder recorder;

    ResolvedServerErrorEventHook(@NonNull ExceptionRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    @Override
    public void onException(@NonNull Exception exception,
                            @NonNull ErrorEntry entry,
                            @NonNull String correlationId) {
        if (!entry.getHttpStatus().is5xxServerError()) {
            return;
        }

        recorder.recordEvent(Span.current(), exception);
    }
}
