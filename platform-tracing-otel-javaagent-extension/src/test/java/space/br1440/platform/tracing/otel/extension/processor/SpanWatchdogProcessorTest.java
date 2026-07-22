package space.br1440.platform.tracing.otel.extension.processor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SpanWatchdogProcessorTest {

    @Test
    void принудительно_закрывает_span_по_таймауту_и_проставляет_атрибут_platform_timeout() throws InterruptedException {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(Duration.ofMillis(50), Duration.ofMinutes(1));

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("test");

        Span longSpan = tracer.spanBuilder("hung-op").startSpan();
        Thread.sleep(150);

        watchdog.scanForTesting();

        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        var data = exporter.getFinishedSpanItems().get(0);
        assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT))).isEqualTo("span");
        assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT))).isEqualTo("timeout");
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(watchdog.getForcedSpanCloses()).isEqualTo(1);

        // Повторный end() от приложения не должен ломать логику.
        longSpan.end();
        watchdog.shutdown();
        tracerProvider.shutdown();
    }

    @Test
    void не_трогает_span_завершившийся_до_таймаута() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(Duration.ofMinutes(1), Duration.ofMinutes(1));

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("test");

        tracer.spanBuilder("fast-op").startSpan().end();
        watchdog.scanForTesting();

        var data = exporter.getFinishedSpanItems().get(0);
        assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT))).isNull();
        assertThat(watchdog.getForcedSpanCloses()).isZero();

        watchdog.shutdown();
        tracerProvider.shutdown();
    }

    @Test
    void принудительно_закрывает_span_по_таймауту_трассы() throws InterruptedException {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(Duration.ofMinutes(1), Duration.ofMillis(50));

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("test");

        Span span = tracer.spanBuilder("trace-root").startSpan();
        Thread.sleep(100);
        watchdog.scanForTesting();

        var data = exporter.getFinishedSpanItems().get(0);
        assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT))).isEqualTo("trace");
        assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT))).isEqualTo("timeout");
        assertThat(watchdog.getForcedTraceCloses()).isEqualTo(1);

        span.end();
        watchdog.shutdown();
        tracerProvider.shutdown();
    }

    @Test
    void forceFlush_закрывает_зависшие_span_синхронно() throws InterruptedException {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(Duration.ofMillis(10), Duration.ofMinutes(1));

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("test");

        tracer.spanBuilder("hung-on-shutdown").startSpan();
        Thread.sleep(50);

        watchdog.forceFlush();

        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        var data = exporter.getFinishedSpanItems().get(0);
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT))).isEqualTo("span");
        assertThat(watchdog.getForcedSpanCloses()).isEqualTo(1);

        watchdog.shutdown();
        tracerProvider.shutdown();
    }

    @Test
    void concurrency_все_внутренние_индексы_очищаются_после_завершения_span_ов() throws InterruptedException {
        // Регрессионный тест на BUG-3 + BUG-4: финальная верификация одновременно проверяет
        // три внутренних индекса (activeSpans, traceStartByTraceId, liveSpanCountByTrace).
        // Без проверки liveSpanCountByTrace баг во вторичном индексе пройдёт незамеченным.
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(Duration.ofMinutes(10), Duration.ofMinutes(10));

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("test");

        int threads = 8;
        int spansPerThread = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < spansPerThread; i++) {
                        Span s = tracer.spanBuilder("op-" + i).startSpan();
                        s.end();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // Все три внутренние карты должны опустеть после нормального закрытия всех span'ов.
        // liveSpanCountByTrace.isEmpty() — критичная проверка инварианта BUG-3.
        assertThat(watchdog.getActiveSpanCount()).isZero();
        assertThat(watchdog.getActiveTraceCount()).isZero();
        assertThat(watchdog.liveTraceCountIndexSizeForTesting()).isZero();
        assertThat(watchdog.getForcedSpanCloses()).isZero();
        assertThat(watchdog.getForcedTraceCloses()).isZero();

        watchdog.shutdown();
        tracerProvider.shutdown();
    }
}
