package space.br1440.platform.tracing.otel.javaagent.propagation;

import java.util.Collection;
import java.util.Objects;
import jakarta.annotation.Nonnull;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;

final class FailClosedCorrelationBaggagePropagator implements TextMapPropagator {

    static final String CORRELATION_BAGGAGE_KEY = "platform.correlation.id";

    private final TextMapPropagator delegate;

    FailClosedCorrelationBaggagePropagator(TextMapPropagator delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Collection<String> fields() {
        return delegate.fields();
    }

    @Override
    public <C> Context extract(@Nonnull Context context, C carrier, @Nonnull TextMapGetter<C> getter) {
        return withoutUnverifiedCorrelation(delegate.extract(context, carrier, getter));
    }

    @Override
    public <C> void inject(@Nonnull Context context, C carrier, @Nonnull TextMapSetter<C> setter) {
        delegate.inject(withoutUnverifiedCorrelation(context), carrier, setter);
    }

    private static Context withoutUnverifiedCorrelation(Context context) {
        Context required = Objects.requireNonNull(context, "context");

        Baggage baggage = Baggage.fromContext(required);
        if (baggage.getEntryValue(CORRELATION_BAGGAGE_KEY) == null) {
            return required;
        }

        return baggage.toBuilder()
                .remove(CORRELATION_BAGGAGE_KEY)
                .build()
                .storeInContext(required);
    }
}
