package space.br1440.platform.tracing.otel.javaagent.propagation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;

class FailClosedCorrelationBaggagePropagatorTest {

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

    private final FailClosedCorrelationBaggagePropagator propagator =
            new FailClosedCorrelationBaggagePropagator(W3CBaggagePropagator.getInstance());

    @Test
    void stripsCanonicalCorrelationOnExtractAndPreservesUnrelatedBaggage() {
        Context extracted = propagator.extract(
                Context.root(),
                Map.of("baggage", "platform.correlation.id=spoofed,tenant_class=internal"),
                GETTER
        );

        Baggage baggage = Baggage.fromContext(extracted);
        assertThat(baggage.getEntryValue(FailClosedCorrelationBaggagePropagator.CORRELATION_BAGGAGE_KEY))
                .isNull();
        assertThat(baggage.getEntryValue("tenant_class")).isEqualTo("internal");
    }

    @Test
    void stripsCanonicalCorrelationOnInjectAndPreservesUnrelatedBaggage() {
        Context context = Baggage.builder()
                .put(FailClosedCorrelationBaggagePropagator.CORRELATION_BAGGAGE_KEY, "workflow-42")
                .put("tenant_class", "internal")
                .build()
                .storeInContext(Context.root());
        Map<String, String> carrier = new ConcurrentHashMap<>();

        propagator.inject(context, carrier, Map::put);

        assertThat(carrier.get("baggage"))
                .contains("tenant_class=internal")
                .doesNotContain(FailClosedCorrelationBaggagePropagator.CORRELATION_BAGGAGE_KEY);
    }

    @Test
    // given: F0 не содержит catch-блока — исключение из его собственной pre-processing логики
    // (Baggage.fromContext на null-context, до вызова delegate.inject) должно пробрасываться
    void injectWithNullContextPropagatesExceptionBeforeDelegateIsInvoked() {
        Map<String, String> carrier = new ConcurrentHashMap<>();

        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> propagator.inject(null, carrier, Map::put));

        assertThat(carrier).isEmpty();
    }

    @Test
    // given: F0 не содержит catch-блока — исключение из его собственной post-processing логики
    // (Baggage.fromContext на null-результате delegate.extract) должно пробрасываться
    void extractWithDelegateReturningNullPropagatesException() {
        FailClosedCorrelationBaggagePropagator propagatorOverNullDelegate =
                new FailClosedCorrelationBaggagePropagator(new io.opentelemetry.context.propagation.TextMapPropagator() {
                    @Override
                    public java.util.Collection<String> fields() {
                        return java.util.List.of();
                    }

                    @Override
                    public <C> void inject(Context context, C carrier, io.opentelemetry.context.propagation.TextMapSetter<C> setter) {
                    }

                    @Override
                    public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
                        return null;
                    }
                });

        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> propagatorOverNullDelegate.extract(Context.root(), Map.of(), GETTER));
    }

    @Test
    void removesCanonicalCorrelationAlreadyPresentInInputContext() {
        Context input = Baggage.builder()
                .put(FailClosedCorrelationBaggagePropagator.CORRELATION_BAGGAGE_KEY, "preexisting")
                .put("tenant_class", "internal")
                .build()
                .storeInContext(Context.root());

        Context extracted = propagator.extract(input, Map.of(), GETTER);

        Baggage baggage = Baggage.fromContext(extracted);
        assertThat(baggage.getEntryValue(FailClosedCorrelationBaggagePropagator.CORRELATION_BAGGAGE_KEY))
                .isNull();
        assertThat(baggage.getEntryValue("tenant_class")).isEqualTo("internal");
    }
}
