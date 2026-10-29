package space.br1440.platform.tracing.otel.javaagent.propagation;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Predicate;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.propagation.PlatformBaggageKeys;
import space.br1440.platform.tracing.otel.javaagent.configuration.BaggageKeyPolicy;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;

final class PlatformBaggagePropagator implements TextMapPropagator {

    private final TextMapPropagator delegate;
    private final BaggageKeyPolicy outboundPolicy;

    PlatformBaggagePropagator(TextMapPropagator delegate, BaggageKeyPolicy outboundPolicy) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.outboundPolicy = Objects.requireNonNull(outboundPolicy, "outboundPolicy");
    }

    @Override
    public Collection<String> fields() {
        return delegate.fields();
    }

    @Override
    public <C> Context extract(@Nonnull Context context, C carrier, @Nonnull TextMapGetter<C> getter) {
        Objects.requireNonNull(context, "context");
        Context extracted = Objects.requireNonNull(delegate.extract(context, carrier, getter), "extractedContext");
        return stripReserved(extracted);
    }

    @Override
    public <C> void inject(@Nonnull Context context, C carrier, @Nonnull TextMapSetter<C> setter) {
        Objects.requireNonNull(context, "context");
        delegate.inject(applyOutboundPolicy(context), carrier, setter);
    }

    private Context stripReserved(Context context) {
        return filter(context, PlatformBaggagePropagator::isReserved);
    }

    private Context applyOutboundPolicy(Context context) {
        return filter(context, key -> isReserved(key) || !outboundPolicy.allows(key));
    }

    private static boolean isReserved(String key) {
        return PlatformBaggageKeys.CORRELATION_ID.equalsIgnoreCase(key);
    }

    private static Context filter(Context context, Predicate<String> shouldRemove) {
        Baggage baggage = Baggage.fromContext(context);
        BaggageBuilder filtered = null;
        for (String key : baggage.asMap().keySet()) {
            if (shouldRemove.test(key)) {
                if (filtered == null) {
                    filtered = baggage.toBuilder();
                }

                filtered.remove(key);
            }
        }

        return filtered == null ? context : context.with(filtered.build());
    }
}
