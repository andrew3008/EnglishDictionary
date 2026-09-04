package space.br1440.platform.tracing.otel.javaagent;

import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import space.br1440.platform.tracing.otel.javaagent.testsupport.BootstrapIsolatedClassLoader;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграция Фазы 15: реальный {@link AutoConfiguredOpenTelemetrySdk} с зарегистрированными
 * платформенными SPI ({@code ConfigurableSamplerProvider}, {@code ConfigurablePropagatorProvider})
 * и ENV-aware дефолтом {@code otel.propagators}.
 * <p>
 * SPI расширения подхватываются ServiceLoader'ом из {@code META-INF/services} на test-classpath —
 * как это делает Java Agent в проде.
 */
@DisplayName("Phase 15 SPI: named sampler/propagator через реальный autoconfigure")
@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
class PlatformSpiAutoconfigureIntegrationTest {

    private static AutoConfiguredOpenTelemetrySdkBuilder baseBuilder() {
        return AutoConfiguredOpenTelemetrySdk.builder()
                // Fresh classloader per build isolates the shared ExtensionRuntime
                // coordinator across the several real-ServiceLoader builds in this suite
                // (r5 plan §3 acceptance #9).
                .setServiceClassLoader(BootstrapIsolatedClassLoader.freshBootstrapClassLoader())
                .disableShutdownHook()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.metrics.exporter", "none",
                        "otel.logs.exporter", "none",
                        "otel.traces.exporter", "none"));
    }

    // ---- PR-1: named sampler ------------------------------------------------------------------

    @Test
    @DisplayName("otel.traces.sampler=platform резолвится в платформенный CompositeSampler (без ConfigurationException)")
    void otel_traces_sampler_platform_resolves() {
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                .addPropertiesSupplier(() -> Map.of("otel.traces.sampler", "platform"))
                .build();
        try {
            // SdkTracerProvider.toString() печатает sampler=<getDescription()> (мы добавили toString).
            String description = sdk.getOpenTelemetrySdk().getSdkTracerProvider().toString();
            assertThat(description)
                    .as("named platform sampler должен примениться без двойной обёртки")
                    .contains("PlatformRuleBasedSampler");
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }

    // ---- PR-2: propagator defaults (ENV-aware) ------------------------------------------------

    @Test
    @DisplayName("default: otel.propagators не задан → platform-trace-control добавлен")
    void default_propagators_include_platform() {
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder().build();
        try {
            TextMapPropagator root = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();
            // Платформенные заголовки должны участвовать в fields() (X-Trace-On и др.).
            assertThat(root.fields())
                    .as("platform-trace-control дописан в otel.propagators по умолчанию")
                    .contains("X-Trace-On", "X-QA-Trace", "X-Request-Id");
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }

    @Test
    @DisplayName("ENV-aware: otel.propagators задан через property-source → platform всё равно добавлен")
    void env_propagators_still_get_platform() {
        // Имитация ENV/sysprop: значение приходит из property-source (не из нашего supplier'а дефолтов).
        // addPropertiesSupplier здесь моделирует пользовательский ввод otel.propagators.
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                .addPropertiesSupplier(() -> Map.of("otel.propagators", "tracecontext,baggage"))
                .build();
        try {
            TextMapPropagator root = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();
            assertThat(root.fields())
                    .as("addPropertiesCustomizer (ENV-aware) дописывает platform даже при заданном otel.propagators")
                    .contains("X-Trace-On", "X-QA-Trace", "X-Request-Id")
                    .contains("traceparent");
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }

    @Test
    @DisplayName("явный otel.propagators=...,platform-trace-control → без дубля (fields стабильны)")
    void explicit_propagators_no_duplicate() {
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.propagators", "tracecontext,baggage,platform-trace-control"))
                .build();
        try {
            TextMapPropagator root = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();
            assertThat(root.fields()).contains("X-Trace-On", "X-QA-Trace", "X-Request-Id");
            // fields() — это набор: дубликат platform-trace-control не добавил бы лишних полей,
            // но и не сломал бы composite. Проверяем, что W3C сохранён.
            assertThat(root.fields()).contains("traceparent");
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }

    // ---- Slice 1 (характеризация): pinned OTel 1.64.0 composition lifecycle -------------------

    @Test
    @DisplayName("characterization: tracecontext,baggage,platform-trace-control → final fields без дублей")
    void finalFields_containAllExpectedHeaders_withoutDuplicates_characterization() {
        // given: явный набор из трёх named propagator'ов, как это будет собрано real autoconfigure
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.propagators", "tracecontext,baggage,platform-trace-control"))
                .build();
        try {
            TextMapPropagator root = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();

            // when
            java.util.Collection<String> fields = root.fields();

            // then
            // Проверяем результат применения customizer к individual named propagator до final
            // composite, а не внутренний lifecycle через reflection.
            assertThat(fields).containsExactlyInAnyOrder(
                    "traceparent", "tracestate", "baggage",
                    "X-Trace-On", "X-QA-Trace", "X-Request-Id");
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }

    // ---- Slice 2 (§6.3 плана): W3C outbound baggage через реальный autoconfigure/customizer -----

    @Test
    @DisplayName("integration: outbound baggage через final SDK propagator фильтрует allowlist, "
            + "кодирует %20 и проходит stock round-trip")
    void outboundBaggage_throughRealAutoconfigure_filtersAndRoundTrips() {
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.propagators", "tracecontext,baggage",
                        "platform.tracing.baggage.propagation.outbound-allowed-keys", "tenant_class"))
                .build();
        try {
            TextMapPropagator root = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();

            // given: allowlisted key с пробелом и metadata + denied key
            io.opentelemetry.context.Context ctx = io.opentelemetry.context.Context.root().with(
                    io.opentelemetry.api.baggage.Baggage.builder()
                            .put("tenant_class", "DF 28",
                                    io.opentelemetry.api.baggage.BaggageEntryMetadata.create("verified=true"))
                            .put("internal_secret", "leak")
                            .build());
            Map<String, String> carrier = new java.util.HashMap<>();

            // when: inject через реальный final composite propagator, полученный из autoconfigure
            root.inject(ctx, carrier, (c, k, v) -> c.put(k, v));

            // then: реальный autoconfigure callback применил customizer к direct W3CBaggagePropagator
            // (а не к обёрнутому composite) — заголовок использует stock %20, а не ручной '+'
            String header = carrier.get("baggage");
            assertThat(header)
                    .as("итоговый final SDK propagator обязан кодировать пробел как %%20")
                    .contains("tenant_class=DF%2028")
                    .doesNotContain("internal_secret")
                    .doesNotContain("leak");

            // and: тот же final propagator должен вернуть исходное значение через extract
            io.opentelemetry.context.Context extracted =
                    root.extract(io.opentelemetry.context.Context.root(), carrier,
                            new io.opentelemetry.context.propagation.TextMapGetter<Map<String, String>>() {
                                @Override
                                public Iterable<String> keys(Map<String, String> c) {
                                    return c.keySet();
                                }

                                @Override
                                public String get(Map<String, String> c, String key) {
                                    return c == null ? null : c.get(key);
                                }
                            });
            assertThat(io.opentelemetry.api.baggage.Baggage.fromContext(extracted).getEntryValue("tenant_class"))
                    .isEqualTo("DF 28");
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }

    @Test
    @DisplayName("otel.propagators=none → платформенный пропагатор НЕ добавляется")
    void propagators_none_excludes_platform() {
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                .addPropertiesSupplier(() -> Map.of("otel.propagators", "none"))
                .build();
        try {
            TextMapPropagator root = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();
            assertThat(root.fields())
                    .as("none отключает всю пропагацию — платформенные заголовки не участвуют")
                    .doesNotContain("X-Trace-On", "X-QA-Trace", "X-Request-Id");
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }
}
