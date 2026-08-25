package space.br1440.platform.tracing.otel.javaagent.propagation;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;

final class SafeTextMapPropagator implements TextMapPropagator {

    private final TextMapPropagator delegate;
    private final TracingDiagnostics diagnostics;

    SafeTextMapPropagator(TextMapPropagator delegate) {
        this(delegate, TracingDiagnostics.shared());
    }

    SafeTextMapPropagator(TextMapPropagator delegate, TracingDiagnostics diagnostics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    @Override
    public Collection<String> fields() {
        try {
            Collection<String> fields = delegate.fields();
            return (fields != null) ? fields : List.of();
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            diagnostics.recordPropagatorFailure();
            return List.of();
        }
    }

    @Override
    public <C> Context extract(@Nonnull Context context, C carrier, @Nonnull TextMapGetter<C> getter) {
        try {
            Context extracted = delegate.extract(context, carrier, getter);
            return (extracted != null) ? extracted : context;
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            diagnostics.recordPropagatorFailure();
            return context;
        }
    }

    @Override
    public <C> void inject(@Nonnull Context context, C carrier, @Nonnull TextMapSetter<C> setter) {
        try {
            delegate.inject(context, carrier, setter);
        } catch (Throwable ex) {
            PlatformThrowables.propagateIfFatal(ex);
            diagnostics.recordPropagatorFailure();
        }
    }
}
