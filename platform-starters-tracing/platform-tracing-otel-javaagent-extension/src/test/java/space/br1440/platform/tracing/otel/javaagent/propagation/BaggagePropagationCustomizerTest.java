package space.br1440.platform.tracing.otel.javaagent.propagation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.configuration.BaggageExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;

@DisplayName("BaggagePropagationCustomizer")
class BaggagePropagationCustomizerTest {

    private final TracingDiagnostics diagnostics = new TracingDiagnostics();
    private final BaggagePropagationCustomizer customizer = new BaggagePropagationCustomizer(diagnostics);

    @Test
    @DisplayName("W3C baggage + baggage enabled → разрешённый baggage передаётся в заголовке")
    void w3cBaggage_enabled_injectsAllowedBaggage() {
        // given
        TextMapPropagator propagator = customizer.apply(
                W3CBaggagePropagator.getInstance(),
                configWithBaggageEnabled(true)
        );
        Context context = Context.root().with(Baggage.builder().put("traffic_source", "qa").build());
        Map<String, String> carrier = new HashMap<>();

        // when
        propagator.inject(context, carrier, Map::put);

        // then
        assertThat(carrier).containsExactlyEntriesOf(Map.of("baggage", "traffic_source=qa"));
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
        result.inject(ctx, carrier, (c, k, v) -> Objects.requireNonNull(c, "carrier").put(k, v));

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
    @DisplayName("собранная цепочка фильтрует allowlist и убирает platform.correlation.id одновременно")
    void assembledChain_filtersAllowlistAndStripsCorrelation() {
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
        chain.inject(ctx, carrier, (c, k, v) -> Objects.requireNonNull(c, "carrier").put(k, v));

        // then
        assertThat(carrier).containsExactlyEntriesOf(Map.of("baggage", "traffic_source=qa"));
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
        chain.inject(ctx, carrier, (c, k, v) -> Objects.requireNonNull(c, "carrier").put(k, v));

        // then
        String header = carrier.get("baggage");
        assertThat(header)
                .contains("traffic_source")
                .doesNotContain("access_token");
    }

    @Test
    @DisplayName("ошибка чтения carrier сохраняет допустимый baggage и удаляет reserved-ключ")
    void assembledChain_stripsCorrelationAfterExtractionFailure() {
        // given
        TextMapPropagator chain = customizer.apply(
                W3CBaggagePropagator.getInstance(),
                configWithBaggageEnabled(true)
        );

        Context input = Context.root().with(Baggage.builder()
                .put("platform.correlation.id", "unverified-workflow")
                .put("traffic_source", "qa")
                .build());

        TextMapGetter<Map<String, String>> failingGetter = new TextMapGetter<>() {
            @Override
            public Iterable<String> keys(Map<String, String> carrier) {
                return carrier.keySet();
            }

            @Override
            public String get(Map<String, String> carrier, String key) {
                throw new IllegalStateException("Carrier read failed");
            }
        };

        // when
        Context extracted = chain.extract(input, Map.of("baggage", "traffic_source=remote"), failingGetter);

        // then
        assertThat(diagnostics.getPropagatorFailures()).isEqualTo(1);
        Baggage baggage = Baggage.fromContext(extracted);
        assertThat(baggage.size()).isEqualTo(1);
        assertThat(baggage.getEntryValue("traffic_source")).isEqualTo("qa");
        assertThat(baggage.getEntryValue("platform.correlation.id")).isNull();
        assertThat(Baggage.fromContext(input).getEntryValue("platform.correlation.id"))
                .isEqualTo("unverified-workflow");
    }

    private static BaggageExtensionConfig configWithBaggageEnabled(boolean enabled) {
        ConfigProperties props = DefaultConfigProperties.createFromMap(
                Map.of(ExtensionPropertyNames.BAGGAGE_PROPAGATION_ENABLED, String.valueOf(enabled))
        );
        return new ExtensionConfig(props).baggage();
    }

    @SuppressWarnings("SameParameterValue")
    private static BaggageExtensionConfig configWithOutboundAllowlist(String allowedKeys) {
        ConfigProperties props = DefaultConfigProperties.createFromMap(Map.of(
                ExtensionPropertyNames.BAGGAGE_PROPAGATION_ENABLED, "true",
                ExtensionPropertyNames.BAGGAGE_OUTBOUND_HEADER_ALLOWED_KEYS, allowedKeys
        ));
        return new ExtensionConfig(props).baggage();
    }
}
