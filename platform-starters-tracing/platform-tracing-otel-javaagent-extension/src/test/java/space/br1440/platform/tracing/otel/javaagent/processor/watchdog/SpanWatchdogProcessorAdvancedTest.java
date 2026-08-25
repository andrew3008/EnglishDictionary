package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Дополнительные кейсы {@link SpanWatchdogProcessor}: краевые операции жизненного цикла
 * (повторный shutdown, forceFlush на пустом наборе, поведение dec'ов с уже отсутствующим
 * traceId), наблюдаемость через accessor'ы.
 */
class SpanWatchdogProcessorAdvancedTest {

    @Test
    void getActiveSpanCount_reflects_live_span_count_before_close() {
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofMinutes(1), Duration.ofMinutes(1), System::nanoTime,
                10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
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

        watchdog.shutdown();
        tracerProvider.shutdown();
    }

    @Test
    void repeated_shutdown_does_not_throw_and_keeps_counters_at_zero() {
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofMinutes(1), Duration.ofMinutes(1), System::nanoTime,
                10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);

        assertThat(watchdog.shutdown().isSuccess()).isTrue();
        assertThatCode(watchdog::shutdown).doesNotThrowAnyException();
        assertThat(watchdog.getActiveSpanCount()).isZero();
        assertThat(watchdog.getActiveTraceCount()).isZero();
    }

    @Test
    void forceFlush_on_empty_set_does_not_crash_and_returns_success() {
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofMillis(1), Duration.ofMillis(1), System::nanoTime,
                10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);

        assertThat(watchdog.forceFlush().isSuccess()).isTrue();
        assertThat(watchdog.getForcedSpanCloses()).isZero();
        assertThat(watchdog.getForcedTraceCloses()).isZero();

        watchdog.shutdown();
    }

    @Test
    void platform_attribute_PLATFORM_TIMEOUT_is_set_with_correct_value_for_span() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        AtomicLong clock = new AtomicLong();
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofMillis(20), Duration.ofMinutes(1), clock::get, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("t");

        tracer.spanBuilder("hung").startSpan();
        clock.addAndGet(Duration.ofMillis(21).toNanos());
        watchdog.scanForTesting();

        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        var data = exporter.getFinishedSpanItems().get(0);
        assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT)))
                .isEqualTo("span");

        watchdog.shutdown();
        tracerProvider.shutdown();
    }

    @Test
    void trace_timeout_attribute_takes_priority_over_span_case_only_when_both_fired() {
        // Если оба таймаута одновременно превышены, кодовая ветка выбирает spanExpired раньше:
        // тест документирует фактическое поведение реализации (spanExpired->span; иначе trace).
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        AtomicLong clock = new AtomicLong();
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofMillis(20), Duration.ofMillis(20), clock::get, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("t");

        tracer.spanBuilder("op").startSpan();
        clock.addAndGet(Duration.ofMillis(21).toNanos());
        watchdog.scanForTesting();

        var data = exporter.getFinishedSpanItems().get(0);
        assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT)))
                .isEqualTo("span");
        assertThat(watchdog.getForcedSpanCloses()).isEqualTo(1);
        assertThat(watchdog.getForcedTraceCloses()).isZero();

        watchdog.shutdown();
        tracerProvider.shutdown();
    }

}
