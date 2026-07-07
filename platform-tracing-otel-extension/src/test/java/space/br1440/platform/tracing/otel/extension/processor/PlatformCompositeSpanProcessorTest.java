package space.br1440.platform.tracing.otel.extension.processor;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.internal.ExtendedSpanProcessor;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.extension.exception.TracingValidationException;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class PlatformCompositeSpanProcessorTest {

    @Test
    void пустой_список_делегатов_не_роняет_композит() {
        PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of());

        // Все aggregate-флаги должны быть false — иначе SDK будет его без надобности дёргать.
        assertThat(composite.isStartRequired()).isFalse();
        assertThat(composite.isEndRequired()).isFalse();
        assertThat(composite.isOnEndingRequired()).isFalse();

        // shutdown / forceFlush должны вернуть успех (composite agg-result от пустой коллекции — success).
        assertThat(composite.shutdown().isSuccess()).isTrue();
        assertThat(composite.forceFlush().isSuccess()).isTrue();
    }

    @Test
    void исключение_в_одном_делегате_не_прерывает_остальные_и_инкрементирует_счётчик() {
        AtomicInteger okCalls = new AtomicInteger();

        SpanProcessor faulty = new NamedFaultyProcessor("faulty-onstart");
        SpanProcessor ok = new SpanProcessor() {
            @Override
            public void onStart(Context parentContext, ReadWriteSpan span) {
                okCalls.incrementAndGet();
            }
            @Override
            public boolean isStartRequired() { return true; }
            @Override
            public void onEnd(ReadableSpan span) { }
            @Override
            public boolean isEndRequired() { return false; }
        };

        PlatformCompositeSpanProcessor composite =
                new PlatformCompositeSpanProcessor(List.of(faulty, ok));

        try (SpanProcessorHarness h = SpanProcessorHarness.of(composite)) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.end();

            // Span должен быть экспортирован — non-blocking гарантия.
            assertThat(h.exporter().getFinishedSpanItems()).hasSize(1);
        }

        // Второй делегат всё равно был вызван — изоляция исключения работает.
        assertThat(okCalls.get()).isEqualTo(1);
        // Self-diagnostic: счётчик инкрементировался для имени класса faulty-делегата.
        assertThat(composite.getProcessorErrorCounts())
                .containsEntry("NamedFaultyProcessor", 1L);
    }

    @Test
    void onEnding_вызывается_только_для_ExtendedSpanProcessor() {
        AtomicInteger onEndingCalls = new AtomicInteger();

        SpanProcessor plain = new SpanProcessor() {
            @Override
            public void onStart(Context parentContext, ReadWriteSpan span) { }
            @Override
            public boolean isStartRequired() { return true; }
            @Override
            public void onEnd(ReadableSpan span) { }
            @Override
            public boolean isEndRequired() { return false; }
        };

        ExtendedSpanProcessor extended = new ExtendedSpanProcessor() {
            @Override
            public void onEnding(ReadWriteSpan span) {
                onEndingCalls.incrementAndGet();
            }
            @Override
            public boolean isOnEndingRequired() { return true; }
            @Override
            public void onStart(Context parentContext, ReadWriteSpan span) { }
            @Override
            public boolean isStartRequired() { return false; }
            @Override
            public void onEnd(ReadableSpan span) { }
            @Override
            public boolean isEndRequired() { return false; }
            @Override
            public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
            @Override
            public CompletableResultCode forceFlush() { return CompletableResultCode.ofSuccess(); }
        };

        PlatformCompositeSpanProcessor composite =
                new PlatformCompositeSpanProcessor(List.of(plain, extended));

        // Aggregate-флаг должен корректно учесть extended.
        assertThat(composite.isOnEndingRequired()).isTrue();

        try (SpanProcessorHarness h = SpanProcessorHarness.of(composite)) {
            Tracer tracer = h.tracer("t");
            tracer.spanBuilder("op").startSpan().end();
        }

        assertThat(onEndingCalls.get()).isEqualTo(1);
    }

    @Test
    void getProcessorErrorCounts_возвращает_immutable_snapshot() {
        SpanProcessor faulty = new NamedFaultyProcessor("faulty");
        PlatformCompositeSpanProcessor composite =
                new PlatformCompositeSpanProcessor(List.of(faulty));

        // Изначально счётчик равен 0 — карта содержит ключ.
        assertThat(composite.getProcessorErrorCounts())
                .containsEntry("NamedFaultyProcessor", 0L);

        // Попытка модификации возвращённой карты должна бросать UnsupportedOperationException.
        var snapshot = composite.getProcessorErrorCounts();
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> snapshot.put("hacked", 99L));
    }

    // -------------------------------------------------------------------------
    // SP-13 — Composite validation exception behavior characterization
    // -------------------------------------------------------------------------

    /**
     * SP-13 / Test 1.
     * TracingValidationException брошенный из onEnding делегата намеренно пробрасывается
     * из PlatformCompositeSpanProcessor.onEnding (§37: единственное исключение из политики
     * изоляции — fail-fast для CI/pre-prod strict validation).
     */
    @Test
    void rethrowsTracingValidationExceptionFromOnEnding() {
        PlatformCompositeSpanProcessor composite =
                new PlatformCompositeSpanProcessor(List.of(new OnEndingThrowsValidation()));

        try (SpanProcessorHarness h = SpanProcessorHarness.of(composite)) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();

            assertThatExceptionOfType(TracingValidationException.class)
                    .isThrownBy(span::end)
                    .withMessageContaining("sp-13-strict");
        }
    }

    /**
     * SP-13 / Test 2.
     * RuntimeException из onEnding изолируется: pipeline не прерывается, следующий
     * делегат вызывается, счётчик ошибок неудавшегося делегата инкрементируется.
     */
    @Test
    void isolatesRuntimeExceptionFromOnEndingAndContinuesPipeline() {
        AtomicInteger secondDelegateCalls = new AtomicInteger();
        OnEndingThrowsRuntime faultyDelegate = new OnEndingThrowsRuntime();
        RecordingOnEndingProcessor recordingDelegate = new RecordingOnEndingProcessor(secondDelegateCalls);

        PlatformCompositeSpanProcessor composite =
                new PlatformCompositeSpanProcessor(List.of(faultyDelegate, recordingDelegate));

        try (SpanProcessorHarness h = SpanProcessorHarness.of(composite)) {
            Tracer tracer = h.tracer("t");
            // span.end() не должен бросать исключение
            tracer.spanBuilder("op").startSpan().end();
        }

        // Второй делегат был вызван — pipeline не прерван
        assertThat(secondDelegateCalls.get()).isEqualTo(1);
        // Счётчик ошибок неудавшегося делегата инкрементировался
        assertThat(composite.getProcessorErrorCounts())
                .containsEntry("OnEndingThrowsRuntime", 1L);
        // У успешного делегата ошибок нет
        assertThat(composite.getProcessorErrorCounts())
                .containsEntry("RecordingOnEndingProcessor", 0L);
    }

    /**
     * SP-13 / Test 3.
     * RuntimeException из onStart изолируется: pipeline не прерывается, следующий
     * делегат вызывается, счётчик ошибок неудавшегося делегата инкрементируется.
     */
    @Test
    void isolatesRuntimeExceptionFromOnStartAndContinuesPipeline() {
        AtomicInteger secondDelegateCalls = new AtomicInteger();

        SpanProcessor faultyOnStart = new SpanProcessor() {
            @Override public void onStart(Context parentContext, ReadWriteSpan span) {
                throw new RuntimeException("sp-13-onstart-fault");
            }
            @Override public boolean isStartRequired() { return true; }
            @Override public void onEnd(ReadableSpan span) { }
            @Override public boolean isEndRequired() { return false; }
        };
        SpanProcessor okOnStart = new SpanProcessor() {
            @Override public void onStart(Context parentContext, ReadWriteSpan span) {
                secondDelegateCalls.incrementAndGet();
            }
            @Override public boolean isStartRequired() { return true; }
            @Override public void onEnd(ReadableSpan span) { }
            @Override public boolean isEndRequired() { return false; }
        };

        PlatformCompositeSpanProcessor composite =
                new PlatformCompositeSpanProcessor(List.of(faultyOnStart, okOnStart));

        try (SpanProcessorHarness h = SpanProcessorHarness.of(composite)) {
            Tracer tracer = h.tracer("t");
            tracer.spanBuilder("op").startSpan().end();
        }

        // Второй делегат всё равно был вызван в onStart
        assertThat(secondDelegateCalls.get()).isEqualTo(1);
        // Счётчик ошибок первого делегата инкрементировался (анонимный класс → FQCN-ключ)
        long totalOnStartErrors = composite.getProcessorErrorCounts().values().stream()
                .mapToLong(Long::longValue).sum();
        assertThat(totalOnStartErrors).isGreaterThanOrEqualTo(1L);
    }

    /**
     * SP-13 / Test 4.
     * RuntimeException из onEnd изолируется: pipeline не прерывается, следующий
     * делегат вызывается, счётчик ошибок неудавшегося делегата инкрементируется.
     */
    @Test
    void isolatesRuntimeExceptionFromOnEndAndContinuesPipeline() {
        AtomicInteger secondDelegateCalls = new AtomicInteger();
        OnEndThrowsRuntime faultyDelegate = new OnEndThrowsRuntime();

        SpanProcessor okOnEnd = new SpanProcessor() {
            @Override public void onStart(Context parentContext, ReadWriteSpan span) { }
            @Override public boolean isStartRequired() { return false; }
            @Override public void onEnd(ReadableSpan span) { secondDelegateCalls.incrementAndGet(); }
            @Override public boolean isEndRequired() { return true; }
        };

        PlatformCompositeSpanProcessor composite =
                new PlatformCompositeSpanProcessor(List.of(faultyDelegate, okOnEnd));

        try (SpanProcessorHarness h = SpanProcessorHarness.of(composite)) {
            Tracer tracer = h.tracer("t");
            // span.end() не должен бросать исключение
            tracer.spanBuilder("op").startSpan().end();
        }

        // Второй делегат был вызван — pipeline не прерван
        assertThat(secondDelegateCalls.get()).isEqualTo(1);
        // Счётчик ошибок неудавшегося делегата инкрементировался
        assertThat(composite.getProcessorErrorCounts())
                .containsEntry("OnEndThrowsRuntime", 1L);
    }

    // -- Именованные тестовые делегаты для SP-13 -----------------------------
    // Именованные inner-классы дают стабильный Class.getSimpleName() → стабильные ключи
    // в errorCounts-карте PlatformCompositeSpanProcessor.

    /** Делегат-ExtendedSpanProcessor, бросающий TracingValidationException из onEnding. */
    private static final class OnEndingThrowsValidation implements ExtendedSpanProcessor {
        @Override public void onEnding(ReadWriteSpan span) {
            throw new TracingValidationException("sp-13-strict: missing required attribute");
        }
        @Override public boolean isOnEndingRequired() { return true; }
        @Override public void onStart(Context parentContext, ReadWriteSpan span) { }
        @Override public boolean isStartRequired() { return false; }
        @Override public void onEnd(ReadableSpan span) { }
        @Override public boolean isEndRequired() { return false; }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode forceFlush() { return CompletableResultCode.ofSuccess(); }
    }

    /** Делегат-ExtendedSpanProcessor, бросающий RuntimeException из onEnding. */
    private static final class OnEndingThrowsRuntime implements ExtendedSpanProcessor {
        @Override public void onEnding(ReadWriteSpan span) {
            throw new RuntimeException("sp-13-runtime-fault-in-onEnding");
        }
        @Override public boolean isOnEndingRequired() { return true; }
        @Override public void onStart(Context parentContext, ReadWriteSpan span) { }
        @Override public boolean isStartRequired() { return false; }
        @Override public void onEnd(ReadableSpan span) { }
        @Override public boolean isEndRequired() { return false; }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode forceFlush() { return CompletableResultCode.ofSuccess(); }
    }

    /** Делегат-ExtendedSpanProcessor, записывающий вызовы onEnding для проверки продолжения pipeline. */
    private static final class RecordingOnEndingProcessor implements ExtendedSpanProcessor {
        private final AtomicInteger counter;
        RecordingOnEndingProcessor(AtomicInteger counter) { this.counter = counter; }
        @Override public void onEnding(ReadWriteSpan span) { counter.incrementAndGet(); }
        @Override public boolean isOnEndingRequired() { return true; }
        @Override public void onStart(Context parentContext, ReadWriteSpan span) { }
        @Override public boolean isStartRequired() { return false; }
        @Override public void onEnd(ReadableSpan span) { }
        @Override public boolean isEndRequired() { return false; }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode forceFlush() { return CompletableResultCode.ofSuccess(); }
    }

    /** Делегат-SpanProcessor, бросающий RuntimeException из onEnd. */
    private static final class OnEndThrowsRuntime implements SpanProcessor {
        @Override public void onStart(Context parentContext, ReadWriteSpan span) { }
        @Override public boolean isStartRequired() { return false; }
        @Override public void onEnd(ReadableSpan span) {
            throw new RuntimeException("sp-13-runtime-fault-in-onEnd");
        }
        @Override public boolean isEndRequired() { return true; }
    }

    // -------------------------------------------------------------------------

    private static final class NamedFaultyProcessor implements SpanProcessor {

        @SuppressWarnings("unused") // используется только для контекста имени делегата при возможном расширении
        private final String label;

        NamedFaultyProcessor(String label) {
            this.label = label;
        }

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
            throw new RuntimeException("simulated onStart failure");
        }
        @Override
        public boolean isStartRequired() { return true; }
        @Override
        public void onEnd(ReadableSpan span) { }
        @Override
        public boolean isEndRequired() { return false; }
    }
}
