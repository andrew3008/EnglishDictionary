package space.br1440.platform.tracing.otel.javaagent;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Гарантия отсутствия double-export при opt-in {@code DROP_OLDEST}: один и тот же span
 * должен экспортироваться <b>ровно один раз</b>, не дважды (стоковый BSP + платформенный).
 *
 * <p><b>Механизм:</b> используем custom {@link SpanExporter} (in-memory счётчик) вместо
 * Collector'а — это даёт точное наблюдение, не зависящее от сетевой доступности OTLP
 * (в smoke-тесте {@link space.br1440.platform.tracing.e2e.smoke.BspDropOldestSafetyAgentSmokeTest}
 * OTLP заведомо unreachable, что делает double-export невозможным к измерению через
 * collector).</p>
 *
 * <p>Тест использует реальный {@link AutoConfiguredOpenTelemetrySdk} с платформенным
 * customizer'ом, подмешивая custom exporter через {@code addSpanExporterCustomizer}. После
 * shutdown'а проверяется, что каждый ended span экспортирован ровно 1 раз (по
 * {@code spanId}). Любая утечка стокового BSP с тем же exporter'ом дала бы 2× экспорт.</p>
 */
class BspDropOldestNoDoubleExportTest {

    @Test
    @DisplayName("opt-in DROP_OLDEST: каждый span экспортируется ровно один раз")
    void everySpanExportedExactlyOnceUnderOptIn() {
        CountingExporter counting = new CountingExporter();

        // ВАЖНО: подмена exporter'а через customizer должна быть зарегистрирована ДО
        // платформенного customizer'а — тогда captureExporter платформы захватит наш
        // counting, и PlatformDropOldestExportSpanProcessor будет строиться вокруг него.
        AutoConfiguredOpenTelemetrySdkBuilder builder = AutoConfiguredOpenTelemetrySdk.builder()
                .disableShutdownHook()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.metrics.exporter", "none",
                        "otel.logs.exporter", "none",
                        "otel.traces.sampler", "always_on",
                        "otel.traces.exporter", "otlp",
                        "otel.exporter.otlp.endpoint", "http://127.0.0.1:1",
                        "otel.bsp.schedule.delay", "100",
                        "otel.bsp.export.timeout", "1000",
                        "platform.tracing.queue.overflow-policy", "DROP_OLDEST",
                        // PR-5 ALIGN_TO_EXTENSION_DEFAULTS changed the absent-ratio default from 1.0
                        // to 0.1. This test measures BSP no-double-export, not sampling rates; it
                        // needs all spans to pass through the sampler.
                        "platform.tracing.sampling.ratio", "1.0"
                ))
                .addSpanExporterCustomizer((existing, cfg) -> counting);
        new PlatformAutoConfigurationCustomizer().customize(builder);

        AutoConfiguredOpenTelemetrySdk sdk = builder.build();
        int totalSpans = 50;
        try {
            OpenTelemetry otel = sdk.getOpenTelemetrySdk();
            Tracer tracer = otel.getTracer("no-double-export-test");
            for (int i = 0; i < totalSpans; i++) {
                tracer.spanBuilder("nde-" + i).startSpan().end();
            }
            // Форсируем дренаж: всех ended spans должны попасть в counting ровно один раз.
            sdk.getOpenTelemetrySdk().getSdkTracerProvider().forceFlush().join(5, TimeUnit.SECONDS);
        } finally {
            sdk.getOpenTelemetrySdk().getSdkTracerProvider().shutdown().join(5, TimeUnit.SECONDS);
            sdk.getOpenTelemetrySdk().close();
        }

        // Каждый spanId должен встретиться ровно один раз: отсутствие дубликатов = нет
        // утечки стокового BSP с тем же exporter'ом.
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

    // ---------------------------------------------------------------------------------------------

    /** Exporter с счётчиком вызовов export() и накопленными span'ами. */
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
