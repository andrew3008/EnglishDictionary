package space.br1440.platform.tracing.otel.javaagent.propagation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import space.br1440.platform.tracing.api.propagation.PlatformBaggageKeys;
import space.br1440.platform.tracing.otel.javaagent.configuration.BaggageKeyPolicy;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;

class PlatformBaggagePropagatorTest {

    private static final BaggageKeyPolicy POLICY = new BaggageKeyPolicy(
            Set.of("tenant_class", PlatformBaggageKeys.CORRELATION_ID), List.of());

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

    private final PlatformBaggagePropagator propagator = new PlatformBaggagePropagator(
            W3CBaggagePropagator.getInstance(),
            POLICY
    );

    @ParameterizedTest
    @ValueSource(strings = {"platform.correlation.id", "Platform.Correlation.Id", "PLATFORM.CORRELATION.ID"})
    void stripsReservedCorrelationOnExtractAndPreservesUnrelatedBaggage(String reservedKey) {
        // given
        Map<String, String> carrier = Map.of("baggage", reservedKey + "=spoofed,traffic_source=x");

        // when
        Context extracted = propagator.extract(Context.root(), carrier, GETTER);

        // then
        Baggage baggage = Baggage.fromContext(extracted);
        assertThat(baggage.size()).isEqualTo(1);
        assertThat(baggage.getEntryValue(reservedKey)).isNull();
        assertThat(baggage.getEntryValue("traffic_source")).isEqualTo("x");
    }

    @ParameterizedTest
    @ValueSource(strings = {"platform.correlation.id", "Platform.Correlation.Id", "PLATFORM.CORRELATION.ID"})
    void stripsReservedCorrelationOnInjectEvenWhenPolicyAllowsIt(String reservedKey) {
        // given
        Context context = Baggage.builder()
                .put(reservedKey, "workflow-42")
                .put("tenant_class", "internal")
                .build()
                .storeInContext(Context.root());
        Map<String, String> carrier = new ConcurrentHashMap<>();

        // when
        propagator.inject(context, carrier, SETTER);

        // then
        assertThat(POLICY.allows(reservedKey)).isTrue();
        assertThat(carrier).containsOnlyKeys("baggage");
        assertThat(carrier.get("baggage")).isEqualTo("tenant_class=internal");
    }

    @Test
    @SuppressWarnings({"ConstantConditions", "DataFlowIssue"})
    void injectWithNullContextPropagatesExceptionBeforeDelegateIsInvoked() {
        // given
        TextMapPropagator delegate = mock(TextMapPropagator.class);
        PlatformBaggagePropagator propagator = new PlatformBaggagePropagator(delegate, POLICY);
        Map<String, String> carrier = new ConcurrentHashMap<>();

        // when
        assertThrows(NullPointerException.class,
                () -> propagator.inject(null, carrier, SETTER));

        // then
        verifyNoInteractions(delegate);
        assertThat(carrier).isEmpty();
    }

    @Test
    void extractWithDelegateReturningNullPropagatesException() {
        // given
        PlatformBaggagePropagator propagatorOverNullDelegate =
                new PlatformBaggagePropagator(new TextMapPropagator() {
                    @Override
                    public Collection<String> fields() {
                        return List.of();
                    }

                    @Override
                    public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
                    }

                    @Override
                    public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
                        return null;
                    }
                }, POLICY);

        // when
        // then
        assertThrows(NullPointerException.class,
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

    @Test
    void extractPreservesDelegateContextIdentityWhenReservedKeyIsAbsent() {
        // given
        Context input = Context.root();
        Context extracted = input.with(Baggage.builder().put("not_allowlisted", "x").build());
        Map<String, String> carrier = Map.of();
        TextMapPropagator delegate = mock(TextMapPropagator.class);
        when(delegate.extract(input, carrier, GETTER)).thenReturn(extracted);
        PlatformBaggagePropagator propagator = new PlatformBaggagePropagator(delegate, POLICY);

        // when
        Context result = propagator.extract(input, carrier, GETTER);

        // then
        assertThat(result).isSameAs(extracted);
    }

    @Test
    void injectPreservesContextIdentityWhenAllKeysAreAllowed() {
        // given
        Context input = Context.root().with(Baggage.builder().put("tenant_class", "gold").build());
        Map<String, String> carrier = Map.of();
        TextMapPropagator delegate = mock(TextMapPropagator.class);
        PlatformBaggagePropagator propagator = new PlatformBaggagePropagator(delegate, POLICY);

        // when
        propagator.inject(input, carrier, SETTER);

        // then
        verify(delegate).inject(same(input), same(carrier), same(SETTER));
    }

    @Test
    void injectDelegatesEmptyBaggageWithoutReplacingContext() {
        // given
        Context input = Context.root();
        Map<String, String> carrier = Map.of();
        TextMapPropagator delegate = mock(TextMapPropagator.class);
        PlatformBaggagePropagator propagator = new PlatformBaggagePropagator(delegate, POLICY);

        // when
        propagator.inject(input, carrier, SETTER);

        // then
        verify(delegate).inject(same(input), same(carrier), same(SETTER));
    }

    @Test
    void injectDelegatesFullyFilteredBaggageAndPreservesOtherContextValues() {
        // given
        ContextKey<String> otherKey = ContextKey.named("other");
        Context input = Context.root().with(otherKey, "kept")
                .with(Baggage.builder().put("not_allowlisted", "removed").build());
        Map<String, String> carrier = Map.of();
        TextMapPropagator delegate = mock(TextMapPropagator.class);
        PlatformBaggagePropagator propagator = new PlatformBaggagePropagator(delegate, POLICY);
        ArgumentCaptor<Context> injected = ArgumentCaptor.forClass(Context.class);

        // when
        propagator.inject(input, carrier, SETTER);

        // then
        verify(delegate).inject(injected.capture(), same(carrier), same(SETTER));
        assertThat(Baggage.fromContext(injected.getValue()).isEmpty()).isTrue();
        assertThat(injected.getValue().get(otherKey)).isEqualTo("kept");
        assertThat(Baggage.fromContext(input).getEntryValue("not_allowlisted")).isEqualTo("removed");
    }

    @Test
    @SuppressWarnings({"ConstantConditions", "DataFlowIssue"})
    void extractWithNullContextPropagatesExceptionBeforeDelegateIsInvoked() {
        // given
        TextMapPropagator delegate = mock(TextMapPropagator.class);
        PlatformBaggagePropagator propagator = new PlatformBaggagePropagator(delegate, POLICY);

        // when
        assertThrows(NullPointerException.class, () -> propagator.extract(null, Map.of(), GETTER));

        // then
        verifyNoInteractions(delegate);
    }

    @Test
    void fieldsAreOwnedByDelegate() {
        // given
        Collection<String> fields = List.of("baggage");
        TextMapPropagator delegate = mock(TextMapPropagator.class);
        when(delegate.fields()).thenReturn(fields);
        PlatformBaggagePropagator propagator = new PlatformBaggagePropagator(delegate, POLICY);

        // when
        Collection<String> result = propagator.fields();

        // then
        assertThat(result).isSameAs(fields);
    }
}
