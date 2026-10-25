package space.br1440.platform.tracing.otel.javaagent.propagation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.propagation.PlatformBaggageKeys;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

class FailClosedCorrelationBaggagePropagatorTest {

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            if (carrier == null) {
                return List.of();
            }

            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            if (carrier == null) {
                return null;
            }

            return carrier.get(key);
        }
    };

    private static final TextMapSetter<Map<String, String>> SETTER = (carrier, key, value) -> {
        if (carrier == null || key == null || value == null) {
            return;
        }

        carrier.put(key, value);
    };

    private final FailClosedCorrelationBaggagePropagator propagator =
            new FailClosedCorrelationBaggagePropagator(W3CBaggagePropagator.getInstance());

    @Test
    void stripsCanonicalCorrelationOnExtractAndPreservesUnrelatedBaggage() {
        // when
        Context extracted = propagator.extract(
                Context.root(),
                Map.of("baggage", "platform.correlation.id=spoofed,tenant_class=internal"),
                GETTER
        );

        // then
        Baggage baggage = Baggage.fromContext(extracted);
        assertThat(baggage.getEntryValue(PlatformBaggageKeys.CORRELATION_ID))
                .isNull();
        assertThat(baggage.getEntryValue("tenant_class")).isEqualTo("internal");
    }

    @Test
    void stripsCanonicalCorrelationOnInjectAndPreservesUnrelatedBaggage() {
        // given
        Context context = Baggage.builder()
                .put(PlatformBaggageKeys.CORRELATION_ID, "workflow-42")
                .put("tenant_class", "internal")
                .build()
                .storeInContext(Context.root());
        Map<String, String> carrier = new ConcurrentHashMap<>();

        // when
        propagator.inject(context, carrier, SETTER);

        // then
        assertThat(carrier.get("baggage"))
                .contains("tenant_class=internal")
                .doesNotContain(PlatformBaggageKeys.CORRELATION_ID);
    }

    @Test
    @SuppressWarnings({"ConstantConditions", "DataFlowIssue", "NullableProblems"})
    // given
    void injectWithNullContextPropagatesExceptionBeforeDelegateIsInvoked() {
        Map<String, String> carrier = new ConcurrentHashMap<>();

        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> propagator.inject(null, carrier, SETTER));

        assertThat(carrier).isEmpty();
    }

    @Test
    // given
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
        // given
        Context input = Baggage.builder()
                .put(PlatformBaggageKeys.CORRELATION_ID, "preexisting")
                .put("tenant_class", "internal")
                .build()
                .storeInContext(Context.root());

        // when
        Context extracted = propagator.extract(input, Map.of(), GETTER);

        // then
        Baggage baggage = Baggage.fromContext(extracted);
        assertThat(baggage.getEntryValue(PlatformBaggageKeys.CORRELATION_ID))
                .isNull();
        assertThat(baggage.getEntryValue("tenant_class")).isEqualTo("internal");
    }
}
