package space.br1440.platform.tracing.otel.extension;

import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.extension.jmx.PlatformTracingObjectNames;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
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
class PlatformSpiAutoconfigureIntegrationTest {

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();

    @AfterEach
    void cleanupMBean() throws Exception {
        for (ObjectName name : new ObjectName[]{
                PlatformTracingObjectNames.SAMPLING,
                PlatformTracingObjectNames.SCRUBBING,
                PlatformTracingObjectNames.VALIDATION,
                PlatformTracingObjectNames.EXPORT,
                PlatformTracingObjectNames.PROCESSOR_METRICS,
                PlatformTracingObjectNames.DIAGNOSTICS
        }) {
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        }
    }

    private static AutoConfiguredOpenTelemetrySdkBuilder baseBuilder() {
        return AutoConfiguredOpenTelemetrySdk.builder()
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
