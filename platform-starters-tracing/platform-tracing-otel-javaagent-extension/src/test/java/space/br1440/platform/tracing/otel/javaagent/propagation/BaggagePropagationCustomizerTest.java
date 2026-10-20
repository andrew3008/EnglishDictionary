package space.br1440.platform.tracing.otel.javaagent.propagation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;

import space.br1440.platform.tracing.otel.javaagent.configuration.BaggageExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;

@DisplayName("BaggagePropagationCustomizer")
class BaggagePropagationCustomizerTest {

    private final BaggagePropagationCustomizer customizer = new BaggagePropagationCustomizer();

    @Test
    @DisplayName("W3C baggage + baggage enabled → возвращает единственную F0-границу")
    void w3cBaggage_enabled_returnsWrapped() {
        // given
        TextMapPropagator propagator = W3CBaggagePropagator.getInstance();

        // when
        TextMapPropagator result = customizer.apply(propagator, configWithBaggageEnabled(true));

        // then
        assertThat(result).isInstanceOf(FailClosedCorrelationBaggagePropagator.class);
    }

    @Test
    @DisplayName("W3C baggage + baggage disabled → исходящий заголовок baggage отсутствует")
    void w3cBaggage_disabled_doesNotInjectBaggageHeader() {
        // given
        TextMapPropagator result = customizer.apply(
                W3CBaggagePropagator.getInstance(),
                configWithBaggageEnabled(false)
        );
        io.opentelemetry.context.Context ctx = io.opentelemetry.context.Context.root().with(
                io.opentelemetry.api.baggage.Baggage.builder()
                        .put("traffic_source", "qa")
                        .put("password", "secret123")
                        .put("platform.correlation.id", "workflow-42")
                        .build());
        Map<String, String> carrier = new java.util.HashMap<>();

        // when
        result.inject(ctx, carrier, (c, k, v) -> c.put(k, v));

        // then
        assertThat(result).isSameAs(TextMapPropagator.noop());
        assertThat(result.fields()).isEmpty();
        assertThat(carrier).doesNotContainKey("baggage");
    }

    @Test
    @DisplayName("non-baggage propagator + baggage disabled → возвращает тот же экземпляр")
    void nonBaggage_disabled_returnsSameInstance() {
        // given
        TextMapPropagator propagator = W3CTraceContextPropagator.getInstance();

        // when
        TextMapPropagator result = customizer.apply(propagator, configWithBaggageEnabled(false));

        // then
        assertThat(result).isSameAs(propagator);
    }

    @Test
    @DisplayName("non-baggage propagator + baggage enabled → возвращает тот же экземпляр")
    void nonBaggage_enabled_returnsSameInstance() {
        // given
        TextMapPropagator propagator = W3CTraceContextPropagator.getInstance();

        // when
        TextMapPropagator result = customizer.apply(propagator, configWithBaggageEnabled(true));

        // then
        assertThat(result).isSameAs(propagator);
    }

    @Test
    @DisplayName("composite propagator + baggage enabled → возвращает тот же экземпляр")
    void composite_enabled_returnsSameInstance() {
        // given
        TextMapPropagator composite = TextMapPropagator.composite(
                W3CBaggagePropagator.getInstance(),
                W3CTraceContextPropagator.getInstance()
        );

        // when
        TextMapPropagator result = customizer.apply(composite, configWithBaggageEnabled(true));

        // then
        assertThat(result).isSameAs(composite);
    }

    @Test
    @DisplayName("произвольный TextMapPropagator + baggage enabled → возвращает тот же экземпляр")
    void customPropagator_enabled_returnsSameInstance() {
        // given
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

        // when
        TextMapPropagator result = customizer.apply(dummy, configWithBaggageEnabled(true));

        // then
        assertThat(result).isSameAs(dummy);
    }

    @Test
    @DisplayName("характеризация: собранная цепочка фильтрует allowlist и убирает platform.correlation.id одновременно")
    void assembledChain_filtersAllowlistAndStripsCorrelation_characterization() {
        // given
        TextMapPropagator chain = customizer.apply(W3CBaggagePropagator.getInstance(), configWithBaggageEnabled(true));
        io.opentelemetry.context.Context ctx = io.opentelemetry.context.Context.root().with(
                io.opentelemetry.api.baggage.Baggage.builder()
                        .put("password", "secret123")
                        .put("platform.correlation.id", "unverified-workflow")
                        .put("traffic_source", "qa")
                        .build());
        Map<String, String> carrier = new java.util.HashMap<>();

        // when
        chain.inject(ctx, carrier, (c, k, v) -> c.put(k, v));

        // then
        String header = carrier.get("baggage");
        assertThat(header)
                .doesNotContain("password")
                .doesNotContain("platform.correlation.id")
                .contains("traffic_source");
    }

    @Test
    @DisplayName("W3C baggage + baggage enabled → deny-pattern исключает ключ из allowlist")
    void w3cBaggage_enabled_omitsKeyMatchingDenyPattern() {
        // given
        TextMapPropagator chain = customizer.apply(
                W3CBaggagePropagator.getInstance(),
                configWithOutboundAllowlist("traffic_source,access_token")
        );
        io.opentelemetry.context.Context ctx = io.opentelemetry.context.Context.root().with(
                io.opentelemetry.api.baggage.Baggage.builder()
                        .put("traffic_source", "qa")
                        .put("access_token", "value")
                        .build());
        Map<String, String> carrier = new java.util.HashMap<>();

        // when
        chain.inject(ctx, carrier, (c, k, v) -> c.put(k, v));

        // then
        String header = carrier.get("baggage");
        assertThat(header)
                .contains("traffic_source")
                .doesNotContain("access_token");
    }

    private static BaggageExtensionConfig configWithBaggageEnabled(boolean enabled) {
        ConfigProperties props = DefaultConfigProperties.createFromMap(
                Map.of(ExtensionPropertyNames.BAGGAGE_PROPAGATION_ENABLED, String.valueOf(enabled))
        );
        return new ExtensionConfig(props).baggage();
    }

    private static BaggageExtensionConfig configWithOutboundAllowlist(String allowedKeys) {
        ConfigProperties props = DefaultConfigProperties.createFromMap(Map.of(
                ExtensionPropertyNames.BAGGAGE_PROPAGATION_ENABLED, "true",
                ExtensionPropertyNames.BAGGAGE_OUTBOUND_HEADER_ALLOWED_KEYS, allowedKeys
        ));
        return new ExtensionConfig(props).baggage();
    }
}
