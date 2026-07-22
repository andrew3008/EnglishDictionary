package space.br1440.platform.tracing.otel.extension.spike;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.extension.spike.baggage.RecordingTextMapSetter;
import space.br1440.platform.tracing.otel.extension.spike.baggage.SpikeFilteringBaggagePropagator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 go/no-go spike: outbound baggage filtering через {@code addPropagatorCustomizer}.
 *
 * <p><b>Цель:</b> выбрать стратегию PR-2 (A/B/C) до реализации production {@code FilteringBaggagePropagator}.</p>
 *
 * <p><b>Acceptance:</b></p>
 * <ol>
 *   <li>Можно ли introspect/unwrap {@code existing} composite propagator</li>
 *   <li>Порядок inject при {@code composite(existing, filter)} — filter перезаписывает stock baggage</li>
 *   <li>Setter перезаписывает {@code baggage} header, а не добавляет второй (HTTP map)</li>
 *   <li>Kafka-like carrier (byte headers map) — тот же overwrite semantics</li>
 *   <li>{@code otel.propagators=tracecontext,baggage} и {@code tracecontext,baggage,b3} через autoconfigure SPI</li>
 *   <li>Стратегия replace-baggage-in-chain: {@code password=} не уходит в outbound header</li>
 *   <li>Стратегия append-only filter AFTER existing: доказать leak или overwrite</li>
 * </ol>
 */
class BaggageFilteringSpikeTest {

    private static final Set<String> ALLOWLIST = Set.of("traffic_source", "tenant_class", "correlation-id");
    private static final List<String> DENY = List.of("password", "secret", "token");

    private static AutoConfiguredOpenTelemetrySdkBuilder baseBuilder() {
        return AutoConfiguredOpenTelemetrySdk.builder()
                .disableShutdownHook()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.metrics.exporter", "none",
                        "otel.logs.exporter", "none",
                        "otel.traces.exporter", "none"
                ));
    }

    private static Context contextWithBaggage(String... keyValues) {
        var builder = Baggage.builder();
        for (int i = 0; i < keyValues.length; i += 2) {
            builder.put(keyValues[i], keyValues[i + 1]);
        }
        return Context.root().with(builder.build());
    }

    // ============================================================================================
    // #1 Unwrap existing composite
    // ============================================================================================

    @Nested
    @DisplayName("#1: unwrap existing propagator chain")
    class UnwrapSpike {

        @Test
        @DisplayName("addPropagatorCustomizer вызывается ПО КАЖДОМУ propagator (не на финальный composite)")
        void customizerInvokedPerPropagatorNotOnComposite() {
            List<String> customizerInputs = new ArrayList<>();

            AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                    .addPropertiesSupplier(() -> Map.of("otel.propagators", "tracecontext,baggage"))
                    .addPropagatorCustomizer((propagator, cfg) -> {
                        customizerInputs.add(propagator.getClass().getName());
                        return propagator;
                    })
                    .build();

            try {
                // Фаза 15: платформенный addPropertiesCustomizer дописывает named platform-trace-control
                // в otel.propagators (ENV-aware default), поэтому per-propagator вызовов теперь >= 3.
                // Ключевой вывод spike сохраняется: customizer вызывается ПО КАЖДОМУ propagator (>1),
                // а не на финальный composite (был бы ровно 1).
                assertThat(customizerInputs)
                        .as("PropagatorConfiguration: customizer.apply() на каждый entry из otel.propagators")
                        .hasSizeGreaterThanOrEqualTo(2)
                        .anyMatch(n -> n.contains("W3CTraceContextPropagator"))
                        .anyMatch(n -> n.contains("W3CBaggagePropagator"));
                System.out.println("[spike] customizer inputs (per propagator) = " + customizerInputs);
            } finally {
                sdk.getOpenTelemetrySdk().close();
            }
        }

        @Test
        @DisplayName("default otel.propagators=tracecontext,baggage → финальный SDK propagator = MultiTextMapPropagator")
        void defaultPropagatorsAreCompositeWithBaggage() {
            AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                    .addPropertiesSupplier(() -> Map.of("otel.propagators", "tracecontext,baggage"))
                    .build();

            try {
                TextMapPropagator root = sdk.getOpenTelemetrySdk()
                        .getPropagators()
                        .getTextMapPropagator();
                assertThat(root.getClass().getName()).contains("MultiTextMapPropagator");

                List<TextMapPropagator> parts = SpikeFilteringBaggagePropagator.unwrapPropagators(root);
                assertThat(parts).hasSizeGreaterThanOrEqualTo(2);
                List<String> names = parts.stream().map(p -> p.getClass().getSimpleName()).toList();
                assertThat(names).anyMatch(n -> n.contains("W3CTraceContextPropagator"));
                // В PR-2 W3CBaggagePropagator был заменён на FilteringBaggagePropagator, а в Фазе 11
                // фильтрующий и control propagator'ы обёрнуты в SafeTextMapPropagator (safe boundary).
                assertThat(names).anyMatch(n -> n.contains("SafeTextMapPropagator"));

                System.out.println("[spike] final SDK propagator parts = "
                        + parts.stream().map(p -> p.getClass().getName()).toList());
            } finally {
                sdk.getOpenTelemetrySdk().close();
            }
        }

        @Test
        @DisplayName("otel.propagators=tracecontext,baggage,b3 → три propagator'а в финальном SDK chain")
        void baggageAndB3Coexist() {
            AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                    .addPropertiesSupplier(() -> Map.of("otel.propagators", "tracecontext,baggage,b3"))
                    .build();

            try {
                List<TextMapPropagator> parts = SpikeFilteringBaggagePropagator.unwrapPropagators(
                        sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator());
                assertThat(parts).hasSizeGreaterThanOrEqualTo(3);
                List<String> simple = parts.stream().map(p -> p.getClass().getSimpleName()).toList();
                assertThat(simple).anyMatch(n -> n.contains("W3CTraceContextPropagator"));
                // Фаза 11: FilteringBaggagePropagator обёрнут в SafeTextMapPropagator (safe boundary).
                assertThat(simple).anyMatch(n -> n.contains("SafeTextMapPropagator"));
                assertThat(simple).anyMatch(n -> n.contains("B3Propagator"));
                System.out.println("[spike] tracecontext,baggage,b3 parts = " + simple);
            } finally {
                sdk.getOpenTelemetrySdk().close();
            }
        }
    }

    // ============================================================================================
    // #2/#3 Inject order — append filter AFTER stock composite
    // ============================================================================================

    @Nested
    @DisplayName("#2/#3: inject order composite(existing, filterAppend)")
    class InjectOrderAppendSpike {

        @Test
        @DisplayName("composite(W3C+Baggage, filterAppend): filter ПОСЛЕ stock → перезаписывает baggage header")
        void appendFilterAfterStockOverwritesBaggageHeader() {
            TextMapPropagator stock = TextMapPropagator.composite(
                    W3CTraceContextPropagator.getInstance(),
                    W3CBaggagePropagator.getInstance());
            TextMapPropagator withFilter = TextMapPropagator.composite(
                    stock,
                    new SpikeFilteringBaggagePropagator(ALLOWLIST, DENY));

            Map<String, String> carrier = new LinkedHashMap<>();
            RecordingTextMapSetter<Map<String, String>> recording =
                    new RecordingTextMapSetter<>((c, k, v) -> c.put(k, v));

            Context ctx = contextWithBaggage(
                    "password", "secret123",
                    "traffic_source", "qa",
                    "tenant_class", "gold");

            withFilter.inject(ctx, carrier, recording);

            List<RecordingTextMapSetter.SetCall> baggageCalls = recording.calls().stream()
                    .filter(c -> "baggage".equalsIgnoreCase(c.key()))
                    .toList();

            assertThat(baggageCalls)
                    .as("stock + filter: два inject на baggage (stock полный, filter перезапись)")
                    .hasSizeGreaterThanOrEqualTo(2);

            String finalBaggage = carrier.get("baggage");
            System.out.println("[spike] final baggage header = " + finalBaggage);
            System.out.println("[spike] baggage setter calls = " + baggageCalls);

            assertThat(finalBaggage)
                    .doesNotContain("password")
                    .doesNotContain("secret123")
                    .contains("traffic_source=qa")
                    .contains("tenant_class=gold");
        }

        @Test
        @DisplayName("только stock Baggage без filter → password утекает в header")
        void stockOnlyLeaksPassword() {
            TextMapPropagator stock = W3CBaggagePropagator.getInstance();
            Map<String, String> carrier = new HashMap<>();
            Context ctx = contextWithBaggage("password", "leak", "traffic_source", "ok");
            stock.inject(ctx, carrier, Map::put);
            assertThat(carrier.get("baggage")).contains("password=leak");
        }
    }

    // ============================================================================================
    // #6 Strategy: replace baggage in chain (recommended if append works)
    // ============================================================================================

    @Nested
    @DisplayName("#6: strategy replace-baggage-in-chain")
    class ReplaceBaggageSpike {

        @Test
        @DisplayName("replaceBaggageWithFilter: password не в outbound header")
        void replaceStrategyStripsPassword() {
            TextMapPropagator stock = TextMapPropagator.composite(
                    W3CTraceContextPropagator.getInstance(),
                    W3CBaggagePropagator.getInstance());
            TextMapPropagator replaced =
                    SpikeFilteringBaggagePropagator.replaceBaggageWithFilter(stock, ALLOWLIST, DENY);

            List<TextMapPropagator> parts = SpikeFilteringBaggagePropagator.unwrapPropagators(replaced);
            assertThat(parts.stream().anyMatch(p -> p instanceof SpikeFilteringBaggagePropagator))
                    .as("W3CBaggage заменён на SpikeFilteringBaggagePropagator")
                    .isTrue();
            assertThat(parts.stream().anyMatch(p -> p.getClass().getName().contains("W3CBaggagePropagator")))
                    .isFalse();

            Map<String, String> carrier = new HashMap<>();
            Context ctx = contextWithBaggage("password", "x", "correlation-id", "abc");
            replaced.inject(ctx, carrier, Map::put);

            String baggage = carrier.get("baggage");
            System.out.println("[spike] replace strategy baggage = " + baggage);
            assertThat(baggage == null || !baggage.contains("password")).isTrue();
            assertThat(baggage).contains("correlation-id=abc");
        }

        @Test
        @DisplayName("addPropagatorCustomizer: заменить W3CBaggagePropagator при per-propagator callback")
        void spiReplaceBaggageInCustomizerCallback() {
            AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                    .addPropertiesSupplier(() -> Map.of("otel.propagators", "tracecontext,baggage"))
                    .addPropagatorCustomizer((propagator, cfg) -> {
                        if (propagator.getClass().getName().contains("W3CBaggagePropagator")) {
                            return new SpikeFilteringBaggagePropagator(ALLOWLIST, DENY);
                        }
                        return propagator;
                    })
                    .build();

            try {
                TextMapPropagator propagators = sdk.getOpenTelemetrySdk()
                        .getPropagators()
                        .getTextMapPropagator();
                List<TextMapPropagator> parts =
                        SpikeFilteringBaggagePropagator.unwrapPropagators(propagators);
                assertThat(parts.stream().anyMatch(p -> p instanceof SpikeFilteringBaggagePropagator))
                        .isTrue();
                assertThat(parts.stream().anyMatch(p -> p.getClass().getName().contains("W3CBaggagePropagator")))
                        .isFalse();

                Map<String, String> carrier = new HashMap<>();
                Context ctx = contextWithBaggage("password", "spi-leak", "tenant_class", "silver");
                propagators.inject(ctx, carrier, Map::put);

                assertThat(carrier.get("baggage"))
                        .doesNotContain("password")
                        .contains("tenant_class=silver");
            } finally {
                sdk.getOpenTelemetrySdk().close();
            }
        }
    }

    // ============================================================================================
    // #4 Kafka-like carrier (string key → string value, как HTTP headers в Kafka instrumentation)
    // ============================================================================================

    @Nested
    @DisplayName("#4: Kafka-like header carrier")
    class KafkaCarrierSpike {

        @Test
        @DisplayName("LinkedHashMap carrier: один ключ baggage, overwrite semantics")
        void kafkaStyleMapCarrierOverwrite() {
            TextMapPropagator propagator = TextMapPropagator.composite(
                    W3CTraceContextPropagator.getInstance(),
                    new SpikeFilteringBaggagePropagator(ALLOWLIST, DENY));

            Map<String, String> headers = new LinkedHashMap<>();
            Context ctx = contextWithBaggage("token", "jwt", "traffic_source", "kafka");
            propagator.inject(ctx, headers, (c, k, v) -> c.put(k, v));

            long baggageKeyCount = headers.keySet().stream()
                    .filter(k -> "baggage".equalsIgnoreCase(k))
                    .count();
            assertThat(baggageKeyCount).isEqualTo(1);
            assertThat(headers.get("baggage"))
                    .contains("traffic_source=kafka")
                    .doesNotContain("token");
        }
    }

    // ============================================================================================
    // Spike conclusion document (printed in test for CI log)
    // ============================================================================================

    @Test
    @DisplayName("SPIKE CONCLUSION: рекомендуемая стратегия для PR-2")
    void printSpikeConclusion() {
        /*
         * Выводы spike (2026-06-04):
         *
         * 1. addPropagatorCustomizer получает MultiTextMapPropagator(existing) — unwrap через reflection OK.
         * 2. composite(existing, filterAppend): stock baggage inject ПЕРВЫМ, filter ВТОРЫМ — filter ПЕРЕЗАПИСЫВАЕТ
         *    header → Strategy "append filter" РАБОТАЕТ для HTTP map carrier.
         * 3. replaceBaggageWithFilter(existing): убирает W3CBaggagePropagator из chain — надёжнее, один inject на baggage.
         * 4. КРИТИЧНО: addPropagatorCustomizer вызывается ПО ОДНОМУ propagator (tracecontext, затем baggage),
         *    НЕ на финальный MultiTextMapPropagator. Нельзя делать composite(existing, filter) в customizer.
         * 5. Рекомендация PR-2: в customizer if (W3CBaggagePropagator) return FilteringBaggagePropagator.
         * 6. append composite(existing, filter) работает только если вручную собирать chain — не в SPI callback.
         * 7. Strategy C (client interceptor strip) — запасной путь для external-only deny.
         */
        assertThat(SpikeFilteringBaggagePropagator.unwrapPropagators(
                TextMapPropagator.composite(
                        W3CTraceContextPropagator.getInstance(),
                        W3CBaggagePropagator.getInstance())))
                .hasSize(2);
        System.out.println("[spike] CONCLUSION: replace W3CBaggage in per-propagator addPropagatorCustomizer callback");
    }
}
