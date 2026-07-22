package space.br1440.platform.tracing.otel.javaagent.propagation;

import java.util.Collection;
import java.util.Objects;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;

/**
 * F0-граница для канонического business correlation baggage.
 *
 * <p>До появления утвержденного transport verifier любой вход и выход считается
 * непроверенным. Поэтому канонический ключ удаляется до создания span и перед
 * записью carrier, а остальные корректно разобранные baggage entries сохраняются.
 */
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
    public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
        return withoutUnverifiedCorrelation(delegate.extract(context, carrier, getter));
    }

    @Override
    public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
        delegate.inject(withoutUnverifiedCorrelation(context), carrier, setter);
    }

    private static Context withoutUnverifiedCorrelation(Context context) {
        Baggage baggage = Baggage.fromContext(context);
        if (baggage.getEntryValue(CORRELATION_BAGGAGE_KEY) == null) {
            return context;
        }
        return baggage.toBuilder()
                .remove(CORRELATION_BAGGAGE_KEY)
                .build()
                .storeInContext(context);
    }
}
