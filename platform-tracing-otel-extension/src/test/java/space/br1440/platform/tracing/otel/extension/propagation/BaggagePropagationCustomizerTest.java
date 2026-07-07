package space.br1440.platform.tracing.otel.extension.propagation;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BaggagePropagationCustomizer")
class BaggagePropagationCustomizerTest {

    private final BaggagePropagationCustomizer customizer = new BaggagePropagationCustomizer();

    @Test
    @DisplayName("W3C baggage + baggage enabled → возвращает SafeTextMapPropagator")
    void w3cBaggage_enabled_returnsWrapped() {
        TextMapPropagator propagator = W3CBaggagePropagator.getInstance();
        TextMapPropagator result = customizer.apply(propagator, configWithBaggageEnabled(true));

        assertThat(result).isInstanceOf(SafeTextMapPropagator.class);
    }

    @Test
    @DisplayName("W3C baggage + baggage disabled → возвращает тот же экземпляр")
    void w3cBaggage_disabled_returnsSameInstance() {
        TextMapPropagator propagator = W3CBaggagePropagator.getInstance();
        TextMapPropagator result = customizer.apply(propagator, configWithBaggageEnabled(false));

        assertThat(result).isSameAs(propagator);
    }

    @Test
    @DisplayName("non-baggage propagator + baggage enabled → возвращает тот же экземпляр")
    void nonBaggage_enabled_returnsSameInstance() {
        TextMapPropagator propagator = W3CTraceContextPropagator.getInstance();
        TextMapPropagator result = customizer.apply(propagator, configWithBaggageEnabled(true));

        assertThat(result).isSameAs(propagator);
    }

    @Test
    @DisplayName("composite propagator + baggage enabled → возвращает тот же экземпляр")
    void composite_enabled_returnsSameInstance() {
        TextMapPropagator composite = TextMapPropagator.composite(
                W3CBaggagePropagator.getInstance(),
                W3CTraceContextPropagator.getInstance()
        );
        TextMapPropagator result = customizer.apply(composite, configWithBaggageEnabled(true));

        assertThat(result).isSameAs(composite);
    }

    @Test
    @DisplayName("произвольный TextMapPropagator + baggage enabled → возвращает тот же экземпляр")
    void customPropagator_enabled_returnsSameInstance() {
        TextMapPropagator dummy = new TextMapPropagator() {
            @Override
            public Collection<String> fields() {
                return List.of();
            }

            @Override
            public <C> void inject(io.opentelemetry.context.Context context, C carrier, TextMapSetter<C> setter) {
            }

            @Override
            public <C> io.opentelemetry.context.Context extract(io.opentelemetry.context.Context context, C carrier, TextMapGetter<C> getter) {
                return context;
            }
        };
        TextMapPropagator result = customizer.apply(dummy, configWithBaggageEnabled(true));

        assertThat(result).isSameAs(dummy);
    }

    private static DefaultConfigProperties configWithBaggageEnabled(boolean enabled) {
        return DefaultConfigProperties.createFromMap(
                Map.of(PropagationDefaults.PROP_BAGGAGE_ENABLED, String.valueOf(enabled))
        );
    }
}
