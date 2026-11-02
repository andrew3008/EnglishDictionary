package space.br1440.platform.tracing.otel.exception;

import java.util.Objects;

record RenderedExceptionStack(String text, boolean truncated) {

    RenderedExceptionStack {
        text = Objects.requireNonNull(text, "text");
    }
}
