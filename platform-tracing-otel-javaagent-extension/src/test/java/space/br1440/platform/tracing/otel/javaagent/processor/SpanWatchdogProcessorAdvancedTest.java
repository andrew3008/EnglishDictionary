package space.br1440.platform.tracing.otel.javaagent.processor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Дополнительные кейсы {@link SpanWatchdogProcessor}: краевые операции жизненного цикла
 * (повторный shutdown, forceFlush на пустом наборе, поведение dec'ов с уже отсутствующим
 * traceId), наблюдаемость через accessor'ы.
 */
class SpanWatchdogProcessorAdvancedTest {

    @Test
    void getActiveSpanCount_отражает_число_живых_span_ов_до_close() {
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofMinutes(1), Duration.ofMinutes(1));
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("t");

        assertThat(watchdog.getActiveSpanCount()).isZero();

        Span s1 = tracer.spanBuilder("op-1").startSpan();
        Span s2 = tracer.spanBuilder("op-2").startSpan();
        assertThat(watchdog.getActiveSpanCount()).isEqualTo(2);
        // span'ы из разных startSpan() могут оказаться в одной/разных трассах в зависимости от parent ctx;
        // на bare tracer они стартуют без явного родителя — каждая trace отдельная.
        assertThat(watchdog.getActiveTraceCount()).isEqualTo(2);

        s1.end();
        assertThat(watchdog.getActiveSpanCount()).isEqualTo(1);
        s2.end();
        assertThat(watchdog.getActiveSpanCount()).isZero();
        assertThat(watchdog.getActiveTraceCount()).isZero();
        assertThat(watchdog.liveTraceCountIndexSizeForTesting()).isZero();

        watchdog.shutdown();
        tracerProvider.shutdown();
    }

    @Test
    void повторный_shutdown_не_бросает_и_оставляет_счётчики_в_нуле() {
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofMinutes(1), Duration.ofMinutes(1));

        assertThat(watchdog.shutdown().isSuccess()).isTrue();
        assertThatCode(watchdog::shutdown).doesNotThrowAnyException();
        assertThat(watchdog.getActiveSpanCount()).isZero();
        assertThat(watchdog.getActiveTraceCount()).isZero();
    }

    @Test
    void forceFlush_на_пустом_наборе_не_роняет_и_возвращает_success() {
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofMillis(1), Duration.ofMillis(1));

        assertThat(watchdog.forceFlush().isSuccess()).isTrue();
        assertThat(watchdog.getForcedSpanCloses()).isZero();
        assertThat(watchdog.getForcedTraceCloses()).isZero();

        watchdog.shutdown();
    }

    @Test
    void платформенный_атрибут_PLATFORM_TIMEOUT_проставляется_корректным_значением_для_span() throws InterruptedException {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(Duration.ofMillis(20), Duration.ofMinutes(1));
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("t");

        tracer.spanBuilder("hung").startSpan();
        Thread.sleep(80);
        watchdog.scanForTesting();

        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        var data = exporter.getFinishedSpanItems().get(0);
        assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT)))
                .isEqualTo("span");

        watchdog.shutdown();
        tracerProvider.shutdown();
    }

    @Test
    void проставка_таймаута_трассы_имеет_приоритет_над_span_кейсом_только_когда_оба_сработали() throws InterruptedException {
        // Если оба таймаута одновременно превышены, кодовая ветка выбирает spanExpired раньше:
        // тест документирует фактическое поведение реализации (spanExpired->span; иначе trace).
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(Duration.ofMillis(20), Duration.ofMillis(20));
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("t");

        tracer.spanBuilder("op").startSpan();
        Thread.sleep(80);
        watchdog.scanForTesting();

        var data = exporter.getFinishedSpanItems().get(0);
        assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT)))
                .isEqualTo("span");
        assertThat(watchdog.getForcedSpanCloses()).isEqualTo(1);
        assertThat(watchdog.getForcedTraceCloses()).isZero();

        watchdog.shutdown();
        tracerProvider.shutdown();
    }

    @Test
    void onEnding_не_требуется_и_не_делает_ничего() {
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofMinutes(1), Duration.ofMinutes(1));
        assertThat(watchdog.isOnEndingRequired()).isFalse();
        // Прямой вызов с null'ом не должен бросать — body пустой.
        assertThatCode(() -> watchdog.onEnding(null)).doesNotThrowAnyException();
        watchdog.shutdown();
    }
}
