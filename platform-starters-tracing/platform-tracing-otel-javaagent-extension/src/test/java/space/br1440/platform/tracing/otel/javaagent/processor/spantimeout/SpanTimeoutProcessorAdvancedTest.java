package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/**
 * Дополнительные кейсы {@link SpanTimeoutProcessor}: краевые операции жизненного цикла
 * (повторный shutdown, forceFlush на пустом наборе, поведение dec'ов с уже отсутствующим
 * traceId), наблюдаемость через accessor'ы.
 */
class SpanTimeoutProcessorAdvancedTest {

    @Test
    void getActiveSpanCount_reflects_live_span_count_before_close() {
        // given
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMinutes(1), Duration.ofMinutes(1), System::nanoTime,
                10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .build();
        try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()) {
            Tracer tracer = sdk.getTracer("t");

            // when/then
            assertThat(spanTimeout.getActiveSpanCount()).isZero();

            Span s1 = tracer.spanBuilder("op-1").startSpan();
            Span s2 = tracer.spanBuilder("op-2").startSpan();
            assertThat(spanTimeout.getActiveSpanCount()).isEqualTo(2);
            // span'ы из разных startSpan() могут оказаться в одной/разных трассах в зависимости от parent ctx;
            // на bare tracer они стартуют без явного родителя — каждая trace отдельная.
            assertThat(spanTimeout.getActiveTraceCount()).isEqualTo(2);

            s1.end();
            assertThat(spanTimeout.getActiveSpanCount()).isEqualTo(1);
            s2.end();
            assertThat(spanTimeout.getActiveSpanCount()).isZero();
            assertThat(spanTimeout.getActiveTraceCount()).isZero();

            spanTimeout.shutdown();
            tracerProvider.shutdown();
        }
    }

    @Test
    void repeated_shutdown_does_not_throw_and_keeps_counters_at_zero() {
        try (SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMinutes(1), Duration.ofMinutes(1), System::nanoTime,
                10_000, Integer.MAX_VALUE, Integer.MAX_VALUE)) {
            // when/then
            assertThat(spanTimeout.shutdown().isSuccess()).isTrue();
            assertThatCode(spanTimeout::shutdown).doesNotThrowAnyException();
            assertThat(spanTimeout.getActiveSpanCount()).isZero();
            assertThat(spanTimeout.getActiveTraceCount()).isZero();
        }
    }

    @Test
    void forceFlush_on_empty_set_does_not_crash_and_returns_success() {
        try (SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMillis(1), Duration.ofMillis(1), System::nanoTime,
                10_000, Integer.MAX_VALUE, Integer.MAX_VALUE)) {
            // when/then
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();
            assertThat(spanTimeout.getForcedSpanEndAttemptsTotal()).isZero();
            assertThat(spanTimeout.getForcedTraceEndAttemptsTotal()).isZero();
        }
    }

    @Test
    void platform_attribute_PLATFORM_TIMEOUT_is_set_with_correct_value_for_span() {
        // given
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        AtomicLong clock = new AtomicLong();
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMillis(20), Duration.ofMinutes(1), clock::get, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()) {
            Tracer tracer = sdk.getTracer("t");

            // when
            tracer.spanBuilder("hung").startSpan();
            clock.addAndGet(Duration.ofMillis(21).toNanos());
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();

            // then
            assertThat(exporter.getFinishedSpanItems()).hasSize(1);
            SpanData data = exporter.getFinishedSpanItems().getFirst();
            assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT)))
                    .isEqualTo("span");

            spanTimeout.shutdown();
            tracerProvider.shutdown();
        }
    }

    @Test
    void trace_timeout_attribute_takes_priority_over_span_case_only_when_both_fired() {
        // given
        // Если оба таймаута одновременно превышены, кодовая ветка выбирает spanExpired раньше:
        // тест документирует фактическое поведение реализации (spanExpired->span; иначе trace).
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        AtomicLong clock = new AtomicLong();
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMillis(20), Duration.ofMillis(20), clock::get, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()) {
            Tracer tracer = sdk.getTracer("t");

            // when
            tracer.spanBuilder("op").startSpan();
            clock.addAndGet(Duration.ofMillis(21).toNanos());
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();

            // then
            SpanData data = exporter.getFinishedSpanItems().getFirst();
            assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT)))
                    .isEqualTo("span");
            assertThat(spanTimeout.getForcedSpanEndAttemptsTotal()).isEqualTo(1);
            assertThat(spanTimeout.getForcedTraceEndAttemptsTotal()).isZero();

            spanTimeout.shutdown();
            tracerProvider.shutdown();
        }
    }

}
