package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;

class PlatformDropOldestExportSpanProcessorLifecycleTest {

    @Test
    @DisplayName("forceFlush дренирует очередь и завершается успехом")
    void forceFlushDrainsQueueAndCompletesSuccessfully() {
        // given
        CountingExporter exporter = new CountingExporter();
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .maxQueueSize(64)
                .maxExportBatchSize(8)
                .scheduleDelay(Duration.ofMinutes(10))
                .exportTimeout(Duration.ofSeconds(5))
                .build();

        try (SdkTracerProvider tp = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build()) {
            Tracer t = tp.get("force-flush-test");

            // when
            for (int i = 0; i < 7; i++) {
                t.spanBuilder("s-" + i).startSpan().end();
            }
            assertThat(processor.metricsSnapshot().queueSize()).isEqualTo(7);
            CompletableResultCode flush = processor.forceFlush();
            flush.join(2, TimeUnit.SECONDS);

            // then
            assertThat(flush.isSuccess()).isTrue();
            assertThat(exporter.exported.size()).isEqualTo(7);
            assertThat(processor.metricsSnapshot().queueSize()).isZero();
        }
    }

    @Test
    @DisplayName("shutdown идемпотентен: повторный вызов возвращает тот же CompletableResultCode")
    void shutdownIsIdempotent() {
        // given
        CountingExporter exporter = new CountingExporter();
        try (PlatformDropOldestExportSpanProcessor processor =
                     PlatformDropOldestExportSpanProcessor.builder(exporter).build()) {
            // when
            CompletableResultCode first = processor.shutdown();
            CompletableResultCode second = processor.shutdown();

            // then
            assertThat(second).isSameAs(first);
            first.join(2, TimeUnit.SECONDS);
            assertThat(first.isDone()).isTrue();
        }
    }

    @Test
    @DisplayName("После shutdown новые спаны учитываются как droppedSpansAfterShutdown, не как overflow")
    void onEndAfterShutdownIncrementsAfterShutdownCounter() throws InterruptedException {
        // given
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

        // when
        CompletableResultCode sd = processor.shutdown();
        sd.join(2, TimeUnit.SECONDS);
        for (int i = 0; i < 3; i++) {
            t.spanBuilder("after-" + i).startSpan().end();
        }
        TimeUnit.MILLISECONDS.sleep(50);

        // then
        assertThat(processor.metricsSnapshot().droppedAfterShutdownTotal())
                .as("спаны после shutdown учитываются в выделенный счётчик")
                .isGreaterThanOrEqualTo(3);

        assertThat(processor.metricsSnapshot().droppedOverflowTotal())
                .as("overflow тут не при чём")
                .isZero();

        tp.close();
    }

    @Test
    void exportFailureDuringShutdownDrainFailsShutdown() {
        // given
        SpanExporter failing = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> batch) {
                return CompletableResultCode.ofFailure();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(failing)
                .maxQueueSize(16)
                .maxExportBatchSize(1)
                .scheduleDelay(Duration.ofMinutes(10))
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();

        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build()) {
            // when
            provider.get("shutdown-drain-failure").spanBuilder("span").startSpan().end();
            CompletableResultCode shutdown = processor.shutdown();

            // then
            assertThat(shutdown.join(5, TimeUnit.SECONDS).isDone()).isTrue();
            assertThat(shutdown.isSuccess()).isFalse();
        }
    }

    @Test
    @DisplayName("exportTimeout срабатывает: счётчик exportTimeouts инкрементируется, экспорт неуспешен")
    void exportTimeoutIncrementsCounter() {
        // given
        SpanExporter neverCompletes = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> batch) {
                return new CompletableResultCode(); // никогда не succeed/fail
            }

            @Override public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };

        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(neverCompletes)
                .maxQueueSize(16)
                .maxExportBatchSize(2)
                .scheduleDelay(Duration.ofMillis(50))
                .exportTimeout(Duration.ofMillis(150))
                .build();

        try (SdkTracerProvider tp = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build()) {
            Tracer t = tp.get("timeout-test");

            // when
            t.spanBuilder("a").startSpan().end();
            t.spanBuilder("b").startSpan().end();
            CompletableResultCode flush = processor.forceFlush();
            flush.join(3, TimeUnit.SECONDS);

            // then
            assertThat(flush.isSuccess()).isFalse();
            assertThat(processor.metricsSnapshot().timeoutsTotal()).isPositive();
            assertThat(processor.metricsSnapshot().failuresTotal())
                    .as("timeout — подкатегория export failure")
                    .isGreaterThanOrEqualTo(processor.metricsSnapshot().timeoutsTotal());
        }
    }

    @Test
    void lateDelegateCompletionAfterExportTimeoutDoesNotChangeTheProcessorResult() throws InterruptedException {
        // given
        List<CompletableResultCode> pending = new CopyOnWriteArrayList<>();
        CountDownLatch exportCalled = new CountDownLatch(1);
        CountDownLatch continued = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        SpanExporter completesAfterJoinTimeout = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> batch) {
                if (calls.incrementAndGet() == 1) {
                    CompletableResultCode result = new CompletableResultCode();
                    pending.add(result);
                    exportCalled.countDown();
                    return result;
                }

                continued.countDown();
                return CompletableResultCode.ofSuccess();
            }

            @Override public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };

        PlatformDropOldestExportSpanProcessor processor =
                PlatformDropOldestExportSpanProcessor.builder(completesAfterJoinTimeout)
                        .maxQueueSize(16)
                        .maxExportBatchSize(2)
                        .scheduleDelay(Duration.ofMillis(50))
                        .exportTimeout(Duration.ofMillis(150))
                        .build();

        try (SdkTracerProvider tp = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build()) {
            Tracer tracer = tp.get("late-completion-test");

            // when
            tracer.spanBuilder("a").startSpan().end();
            tracer.spanBuilder("b").startSpan().end();
            assertThat(exportCalled.await(3, TimeUnit.SECONDS)).isTrue();
            CompletableResultCode timedOutFlush = processor.forceFlush();
            timedOutFlush.join(3, TimeUnit.SECONDS);
            long timeoutsAfterJoin = processor.metricsSnapshot().timeoutsTotal();
            long failuresAfterJoin = processor.metricsSnapshot().failuresTotal();

            // then
            assertThat(timedOutFlush.isSuccess()).isFalse();
            assertThat(timeoutsAfterJoin).isEqualTo(1);

            pending.getFirst().succeed();
            assertThat(processor.metricsSnapshot().timeoutsTotal()).isEqualTo(timeoutsAfterJoin);
            assertThat(processor.metricsSnapshot().failuresTotal()).isEqualTo(failuresAfterJoin);

            tracer.spanBuilder("c").startSpan().end();
            assertThat(continued.await(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("Исключение из exporter.export() изолировано: процессор продолжает работать, exportFailures растёт")
    void exporterExceptionIsolatedAndCounted() throws InterruptedException {
        // given
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch continuedAfterFailure = new CountDownLatch(1);
        SpanExporter throwingThenOk = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> batch) {
                if (calls.incrementAndGet() == 1) {
                    throw new RuntimeException("simulated exporter failure");
                }

                continuedAfterFailure.countDown();
                return CompletableResultCode.ofSuccess();
            }

            @Override public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };

        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(throwingThenOk)
                .maxQueueSize(32)
                .maxExportBatchSize(2)
                .scheduleDelay(Duration.ofMillis(50))
                .build();

        try (SdkTracerProvider tp = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build()) {
            Tracer t = tp.get("isolation-test");

            // when
            for (int i = 0; i < 6; i++) {
                t.spanBuilder("x-" + i).startSpan().end();
            }
            processor.forceFlush().join(3, TimeUnit.SECONDS);

            // then
            assertThat(processor.metricsSnapshot().failuresTotal())
                    .as("первый export бросил исключение, изолировано в счётчик")
                    .isGreaterThanOrEqualTo(1);

            assertThat(continuedAfterFailure.await(2, TimeUnit.SECONDS))
                    .as("worker продолжил работать после исключения")
                    .isTrue();

            assertThat(calls.get())
                    .as("worker продолжил работать после исключения и сделал хотя бы один успешный export")
                    .isGreaterThan(1);
        }
    }

    @Test
    @DisplayName("shutdown дренирует очередь до пустоты в пределах shutdownTimeout")
    void shutdownDrainsQueue() {
        // given
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

        // when
        tp.close();
        long deadline = System.currentTimeMillis() + 5000;
        while (exporter.exported.size() < 15 && System.currentTimeMillis() < deadline) {
            try { TimeUnit.MILLISECONDS.sleep(20); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }

        // then
        assertThat(exporter.exported.size())
                .as("shutdown дренировал все спаны")
                .isEqualTo(15);

        assertThat(processor.metricsSnapshot().droppedAfterShutdownTotal())
                .as("очередь была пуста до shutdown timeout — ничего не потеряли")
                .isZero();
    }

    private static final class CountingExporter implements SpanExporter {
        final List<SpanData> exported = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            exported.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
