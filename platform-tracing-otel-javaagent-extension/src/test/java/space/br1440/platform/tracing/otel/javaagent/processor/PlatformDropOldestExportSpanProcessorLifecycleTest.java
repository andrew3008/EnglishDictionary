package space.br1440.platform.tracing.otel.javaagent.processor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lifecycle-тесты {@link PlatformDropOldestExportSpanProcessor}:
 * {@code forceFlush}, {@code shutdown} (включая идемпотентность), worker termination,
 * счётчики {@code droppedSpansAfterShutdown}, {@code exportFailures}, {@code exportTimeouts}.
 */
class PlatformDropOldestExportSpanProcessorLifecycleTest {

    @Test
    @DisplayName("forceFlush дренирует очередь и завершается успехом")
    void forceFlushDrainsQueueAndCompletesSuccessfully() {
        CountingExporter exporter = new CountingExporter();
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .maxQueueSize(64)
                .maxExportBatchSize(8)
                .scheduleDelay(Duration.ofMinutes(10))
                .exportTimeout(Duration.ofSeconds(5))
                .build();
        SdkTracerProvider tp = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build();
        try {
            Tracer t = tp.get("force-flush-test");
            for (int i = 0; i < 7; i++) {
                t.spanBuilder("s-" + i).startSpan().end();
            }
            CompletableResultCode flush = processor.forceFlush();
            flush.join(2, TimeUnit.SECONDS);
            assertThat(flush.isSuccess()).isTrue();
            assertThat(exporter.exported.size()).isEqualTo(7);
            assertThat(processor.getQueueSize()).isZero();
        } finally {
            tp.close();
        }
    }

    @Test
    @DisplayName("shutdown идемпотентен: повторный вызов возвращает тот же CompletableResultCode")
    void shutdownIsIdempotent() {
        CountingExporter exporter = new CountingExporter();
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .build();
        CompletableResultCode first = processor.shutdown();
        CompletableResultCode second = processor.shutdown();
        // Контракт: повторный shutdown возвращает тот же promise (не создаёт новый дренаж).
        assertThat(second).isSameAs(first);
        first.join(2, TimeUnit.SECONDS);
        assertThat(first.isDone()).isTrue();
    }

    @Test
    @DisplayName("После shutdown новые спаны учитываются как droppedSpansAfterShutdown, не как overflow")
    void onEndAfterShutdownIncrementsAfterShutdownCounter() throws InterruptedException {
        CountingExporter exporter = new CountingExporter();
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .maxQueueSize(64)
                .maxExportBatchSize(8)
                .scheduleDelay(Duration.ofMillis(50))
                .build();
        SdkTracerProvider tp = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build();
        Tracer t = tp.get("after-shutdown-test");
        // Сначала закрываем processor (через TracerProvider не делаем — оставим контроль ручным).
        CompletableResultCode sd = processor.shutdown();
        sd.join(2, TimeUnit.SECONDS);
        // Теперь пытаемся выпустить spans — они должны идти в droppedSpansAfterShutdown.
        for (int i = 0; i < 3; i++) {
            t.spanBuilder("after-" + i).startSpan().end();
        }
        // Небольшая пауза, чтобы спаны точно прошли через onEnd.
        TimeUnit.MILLISECONDS.sleep(50);
        assertThat(processor.getDroppedSpansAfterShutdown())
                .as("спаны после shutdown учитываются в выделенный счётчик")
                .isGreaterThanOrEqualTo(3);
        assertThat(processor.getDroppedSpansOverflow())
                .as("overflow тут не при чём")
                .isZero();
        tp.close();
    }

    @Test
    @DisplayName("exportTimeout срабатывает: счётчик exportTimeouts инкрементируется, экспорт неуспешен")
    void exportTimeoutIncrementsCounter() {
        // Exporter, который НИКОГДА не успокаивает CompletableResultCode — moemulируем
        // network-stall. Мы выставляем заведомо короткий exportTimeout, чтобы триггерить таймаут.
        SpanExporter neverCompletes = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> batch) {
                return new CompletableResultCode(); // никогда не succeed/fail
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(neverCompletes)
                .maxQueueSize(16)
                .maxExportBatchSize(2)
                .scheduleDelay(Duration.ofMillis(50))
                .exportTimeout(Duration.ofMillis(150))
                .build();
        SdkTracerProvider tp = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build();
        try {
            Tracer t = tp.get("timeout-test");
            t.spanBuilder("a").startSpan().end();
            t.spanBuilder("b").startSpan().end();
            CompletableResultCode flush = processor.forceFlush();
            flush.join(3, TimeUnit.SECONDS);
            assertThat(flush.isSuccess()).isFalse();
            assertThat(processor.getExportTimeouts()).isPositive();
            assertThat(processor.getExportFailures())
                    .as("timeout — подкатегория export failure")
                    .isGreaterThanOrEqualTo(processor.getExportTimeouts());
        } finally {
            tp.close();
        }
    }

    @Test
    @DisplayName("Исключение из exporter.export() изолировано: процессор продолжает работать, exportFailures растёт")
    void exporterExceptionIsolatedAndCounted() {
        AtomicInteger calls = new AtomicInteger();
        SpanExporter throwingThenOk = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> batch) {
                if (calls.incrementAndGet() == 1) {
                    throw new RuntimeException("simulated exporter failure");
                }
                return CompletableResultCode.ofSuccess();
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(throwingThenOk)
                .maxQueueSize(32)
                .maxExportBatchSize(2)
                .scheduleDelay(Duration.ofMillis(50))
                .build();
        SdkTracerProvider tp = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build();
        try {
            Tracer t = tp.get("isolation-test");
            for (int i = 0; i < 6; i++) {
                t.spanBuilder("x-" + i).startSpan().end();
            }
            processor.forceFlush().join(3, TimeUnit.SECONDS);
            assertThat(processor.getExportFailures())
                    .as("первый export бросил исключение, изолировано в счётчик")
                    .isGreaterThanOrEqualTo(1);
            assertThat(calls.get())
                    .as("worker продолжил работать после исключения и сделал хотя бы один успешный export")
                    .isGreaterThan(1);
        } finally {
            tp.close();
        }
    }

    @Test
    @DisplayName("shutdown дренирует очередь до пустоты в пределах shutdownTimeout")
    void shutdownDrainsQueue() {
        CountingExporter exporter = new CountingExporter();
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .maxQueueSize(64)
                .maxExportBatchSize(8)
                .scheduleDelay(Duration.ofMinutes(10))
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
        SdkTracerProvider tp = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build();
        Tracer t = tp.get("shutdown-drain");
        for (int i = 0; i < 15; i++) {
            t.spanBuilder("d-" + i).startSpan().end();
        }
        // tp.close() вызывает processor.shutdown().
        tp.close();
        // К моменту возврата из close() shutdown'а promise может ещё не быть isDone (мы возвращаем
        // его сразу). Дадим небольшой запас.
        long deadline = System.currentTimeMillis() + 5000;
        while (exporter.exported.size() < 15 && System.currentTimeMillis() < deadline) {
            try { TimeUnit.MILLISECONDS.sleep(20); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        assertThat(exporter.exported.size())
                .as("shutdown дренировал все спаны")
                .isEqualTo(15);
        assertThat(processor.getDroppedSpansAfterShutdown())
                .as("очередь была пуста до shutdown timeout — ничего не потеряли")
                .isZero();
    }

    // ---------------------------------------------------------------------------------------------

    private static final class CountingExporter implements SpanExporter {
        final List<SpanData> exported = new CopyOnWriteArrayList<>();
        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            exported.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }
        @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }
}
