package space.br1440.platform.tracing.otel.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import space.br1440.platform.tracing.otel.javaagent.testsupport.BootstrapIsolatedClassLoader;

import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;

@DisplayName("SPI: named sampler/propagator через реальный autoconfigure")
@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
class PlatformSpiAutoconfigureIntegrationTest {

    private static AutoConfiguredOpenTelemetrySdkBuilder baseBuilder() {
        return AutoConfiguredOpenTelemetrySdk.builder()
                .setServiceClassLoader(BootstrapIsolatedClassLoader.freshBootstrapClassLoader())
                .disableShutdownHook()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.metrics.exporter", "none",
                        "otel.logs.exporter", "none",
                        "otel.traces.exporter", "none")
                );
    }

    @Test
    @DisplayName("otel.traces.sampler=platform резолвится в платформенный CompositeSampler (без ConfigurationException)")
    void otel_traces_sampler_platform_resolves() {
        // given
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                .addPropertiesSupplier(() -> Map.of("otel.traces.sampler", "platform"))
                .build();
        try {
            // when
            String description = sdk.getOpenTelemetrySdk().getSdkTracerProvider().toString();

            // then
            assertThat(description)
                    .as("named platform sampler должен примениться без двойной обёртки")
                    .contains("PlatformRuleBasedSampler");
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }

    @Test
    @DisplayName("default: otel.propagators не задан → platform-trace-control добавлен")
    void default_propagators_include_platform() {
        // given
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder().build();
        try {
            // when
            TextMapPropagator root = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();

            // then
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
        // given
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                .addPropertiesSupplier(() -> Map.of("otel.propagators", "tracecontext,baggage"))
                .build();
        try {
            // when
            TextMapPropagator root = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();

            // then
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
        // given
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.propagators", "tracecontext,baggage,platform-trace-control"))
                .build();
        try {
            // when
            TextMapPropagator root = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();

            // then
            assertThat(root.fields()).contains("X-Trace-On", "X-QA-Trace", "X-Request-Id");
            assertThat(root.fields()).contains("traceparent");
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }

    @Test
    @DisplayName("characterization: tracecontext,baggage,platform-trace-control → final fields без дублей")
    void finalFields_containAllExpectedHeaders_withoutDuplicates_characterization() {
        // given
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.propagators", "tracecontext,baggage,platform-trace-control"))
                .build();
        try {
            TextMapPropagator root = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();

            // when
            java.util.Collection<String> fields = root.fields();

            // then
            assertThat(fields).containsExactlyInAnyOrder(
                    "traceparent", "tracestate", "baggage",
                    "X-Trace-On", "X-QA-Trace", "X-Request-Id"
            );
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }

    @Test
    @DisplayName("integration: outbound baggage через final SDK propagator фильтрует allowlist, "
            + "кодирует %20 и проходит stock round-trip")
    void outboundBaggage_throughRealAutoconfigure_filtersAndRoundTrips() {
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.propagators", "tracecontext,baggage",
                        "platform.tracing.baggage.outbound-header.allowed-keys", "tenant_class"))
                .build();
        try {
            TextMapPropagator root = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();

            // given
            io.opentelemetry.context.Context ctx = io.opentelemetry.context.Context.root().with(
                    io.opentelemetry.api.baggage.Baggage.builder()
                            .put("tenant_class", "DF 28",
                                    io.opentelemetry.api.baggage.BaggageEntryMetadata.create("verified=true"))
                            .put("traffic_source", "organic")
                            .put("internal_secret", "leak")
                            .build());
            Map<String, String> carrier = new java.util.HashMap<>();

            // when
            root.inject(ctx, carrier, (c, k, v) -> Objects.requireNonNull(c, "carrier").put(k, v));

            // then
            String header = carrier.get("baggage");
            assertThat(header)
                    .as("итоговый final SDK propagator обязан кодировать пробел как %%20")
                    .contains("tenant_class=DF%2028")
                    .doesNotContain("traffic_source")
                    .doesNotContain("internal_secret")
                    .doesNotContain("leak");

            // and: тот же final propagator должен вернуть исходное значение через extract
            io.opentelemetry.context.Context extracted =
                    root.extract(io.opentelemetry.context.Context.root(), carrier,
                            new io.opentelemetry.context.propagation.TextMapGetter<>() {
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
        // given
        AutoConfiguredOpenTelemetrySdk sdk = baseBuilder()
                .addPropertiesSupplier(() -> Map.of("otel.propagators", "none"))
                .build();
        try {
            // when
            TextMapPropagator root = sdk.getOpenTelemetrySdk().getPropagators().getTextMapPropagator();

            // then
            assertThat(root.fields())
                    .as("none отключает всю пропагацию — платформенные заголовки не участвуют")
                    .doesNotContain("X-Trace-On", "X-QA-Trace", "X-Request-Id");
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }
}
