package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class BspDropOldestNoDoubleExportTest {

    @Test
    @DisplayName("каждый span экспортируется ровно один раз")
    void everySpanExportedExactlyOnce() {
        // given
        CountingExporter counting = new CountingExporter();

        AutoConfiguredOpenTelemetrySdkBuilder builder = AutoConfiguredOpenTelemetrySdk.builder()
                .setServiceClassLoader(
                        AutoConfigurationCustomizerTestClassLoader.withoutDiscoveredCustomizers())
                .disableShutdownHook()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.metrics.exporter", "none",
                        "otel.logs.exporter", "none",
                        "otel.traces.exporter", "otlp",
                        "otel.exporter.otlp.endpoint", "http://127.0.0.1:1",
                        "otel.bsp.schedule.delay", "100",
                        "otel.bsp.export.timeout", "1000",
                        "platform.tracing.sampling.ratio", "1.0"
                ))
                .addSpanExporterCustomizer((existing, cfg) -> counting);
        new BootstrapAutoConfigurationCustomizerProvider(RuntimeEvidenceTestFixtures.coordinator()).customize(builder);

        // when
        AutoConfiguredOpenTelemetrySdk sdk = builder.build();
        int totalSpans = 50;
        try {
            OpenTelemetry otel = sdk.getOpenTelemetrySdk();
            Tracer tracer = otel.getTracer("no-double-export-test");
            for (int i = 0; i < totalSpans; i++) {
                tracer.spanBuilder("nde-" + i).startSpan().end();
            }

            sdk.getOpenTelemetrySdk().getSdkTracerProvider().forceFlush().join(5, TimeUnit.SECONDS);
        } finally {
            sdk.getOpenTelemetrySdk().getSdkTracerProvider().shutdown().join(5, TimeUnit.SECONDS);
            sdk.getOpenTelemetrySdk().close();
        }

        // then
        Map<String, Long> spanIdCounts = counting.exported.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        s -> s.getSpanContext().getSpanId(),
                        java.util.stream.Collectors.counting()
                ));
        assertThat(spanIdCounts).hasSize(totalSpans);
        spanIdCounts.forEach((spanId, count) ->
                assertThat(count)
                        .as("span %s экспортирован %d раз — ожидается ровно 1", spanId, count)
                        .isEqualTo(1L));
        assertThat(counting.exportCalls.get())
                .as("суммарное число spans в всех export() == totalSpans (точно один проход)")
                .isEqualTo(totalSpans);
    }

    private static final class CountingExporter implements SpanExporter {
        final AtomicInteger exportCalls = new AtomicInteger();
        final List<SpanData> exported = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            exportCalls.addAndGet(batch.size());
            exported.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }
}
