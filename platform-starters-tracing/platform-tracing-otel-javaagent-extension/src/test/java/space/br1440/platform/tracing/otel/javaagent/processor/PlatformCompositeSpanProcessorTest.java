package space.br1440.platform.tracing.otel.javaagent.processor;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.internal.ExtendedSpanProcessor;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformCompositeSpanProcessorTest {

    @Test
    void rejectsMissingBlankAndDuplicateDiagnosticIdentity() {
        SpanProcessor missing = new SpanProcessor() {
            @Override public void onStart(Context parentContext, ReadWriteSpan span) { }
            @Override public boolean isStartRequired() { return false; }
            @Override public void onEnd(ReadableSpan span) { }
            @Override public boolean isEndRequired() { return false; }
        };

        assertThatThrownBy(() -> new PlatformCompositeSpanProcessor(List.of(missing)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not declare");
        assertThatThrownBy(() -> new PlatformCompositeSpanProcessor(List.of(identified("", missing))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid processor diagnostic ID");
        assertThatThrownBy(() -> new PlatformCompositeSpanProcessor(List.of(
                identified("duplicate", missing),
                identified("duplicate", missing))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate processor diagnostic ID");
    }

    @Test
    void empty_delegate_list_does_not_crash_composite() {
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
    void exception_in_one_delegate_does_not_stop_others_and_increments_counter() {
        AtomicInteger okCalls = new AtomicInteger();

        SpanProcessor faulty = new NamedFaultyProcessor("faulty-onstart");
        SpanProcessor ok = identified("test_ok_start", new SpanProcessor() {
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
        });

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
                .containsEntry("test_faulty", 1L);
    }

    @Test
    void onEnding_is_called_only_for_ExtendedSpanProcessor() {
        AtomicInteger onEndingCalls = new AtomicInteger();

        SpanProcessor plain = identified("test_plain", new SpanProcessor() {
            @Override
            public void onStart(Context parentContext, ReadWriteSpan span) { }
            @Override
            public boolean isStartRequired() { return true; }
            @Override
            public void onEnd(ReadableSpan span) { }
            @Override
            public boolean isEndRequired() { return false; }
        });

        ExtendedSpanProcessor extended = identifiedExtended("test_extended", new ExtendedSpanProcessor() {
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
        });

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
    void phaseParticipationIsSnapshotAtConstructionAndIgnoresLaterPredicateChanges() {
        MutablePredicateProcessor mutable = new MutablePredicateProcessor("mutable_predicate");
        PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(mutable));

        // Изменение предикатов после публикации композита не поддерживается: массивы
        // участников фаз снимаются один раз в конструкторе и не перечитываются на dispatch.
        mutable.startRequired = false;
        mutable.endRequired = false;
        mutable.onEndingRequired = false;

        try (SpanProcessorHarness h = SpanProcessorHarness.of(composite)) {
            h.tracer("t").spanBuilder("op").startSpan().end();
        }

        assertThat(mutable.onStartCalls.get()).isEqualTo(1);
        assertThat(mutable.onEndCalls.get()).isEqualTo(1);
        assertThat(mutable.onEndingCalls.get()).isEqualTo(1);
    }

    @Test
    void getProcessorErrorCounts_returns_immutable_snapshot() {
        SpanProcessor faulty = new NamedFaultyProcessor("faulty");
        PlatformCompositeSpanProcessor composite =
                new PlatformCompositeSpanProcessor(List.of(faulty));

        // Изначально счётчик равен 0 — карта содержит ключ.
        assertThat(composite.getProcessorErrorCounts())
                .containsEntry("test_faulty", 0L);

        // Попытка модификации возвращённой карты должна бросать UnsupportedOperationException.
        var snapshot = composite.getProcessorErrorCounts();
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> snapshot.put("hacked", 99L));
    }

    // -------------------------------------------------------------------------
    // SP-13 — Composite validation exception behavior characterization
    // -------------------------------------------------------------------------

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
                .containsEntry("test_on_ending_failure", 1L);
        // У успешного делегата ошибок нет
        assertThat(composite.getProcessorErrorCounts())
                .containsEntry("test_recording", 0L);
    }

    @Test
    void validationViolationUsesValidatorDiagnosticsWithoutCompositeError() {
        AtomicInteger followingDelegateCalls = new AtomicInteger();
        ValidatingSpanProcessor validator = new ValidatingSpanProcessor(TracingSettingsSource.isolatedValidation(true, true, false));
        PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(
                validator,
                new RecordingOnEndingProcessor(followingDelegateCalls)));

        try (SpanProcessorHarness harness = SpanProcessorHarness.of(composite)) {
            harness.tracer("validation-gate")
                    .spanBuilder("intentional-invalid-span")
                    .startSpan()
                    .end();

            assertThat(harness.exporter().getFinishedSpanItems()).hasSize(1);
            assertThat(harness.exporter().getFinishedSpanItems().get(0).getAttributes()
                    .get(AttributeKey.stringKey("platform.validation.missing")))
                    .contains(PlatformAttributes.PLATFORM_TYPE, PlatformAttributes.PLATFORM_RESULT);
        }

        assertThat(validator.getValidationViolationsTotal()).isEqualTo(1L);
        assertThat(followingDelegateCalls.get()).isEqualTo(1);
        assertThat(composite.getProcessorErrorCounts())
                .containsEntry("validation", 0L);
    }

    @Test
    void validSpanDoesNotIncrementValidationViolationCounter() {
        ValidatingSpanProcessor validator = new ValidatingSpanProcessor(TracingSettingsSource.isolatedValidation(true, true, false));
        PlatformCompositeSpanProcessor composite =
                new PlatformCompositeSpanProcessor(List.of(validator));

        try (SpanProcessorHarness harness = SpanProcessorHarness.of(composite)) {
            harness.tracer("validation-gate")
                    .spanBuilder("valid-span")
                    .setAttribute(PlatformAttributes.PLATFORM_TYPE, "http_server")
                    .setAttribute(PlatformAttributes.PLATFORM_RESULT, "success")
                    .startSpan()
                    .end();
            assertThat(harness.exporter().getFinishedSpanItems()).hasSize(1);
        }

        assertThat(validator.getValidationViolationsTotal()).isZero();
        assertThat(composite.getProcessorErrorCounts())
                .containsEntry("validation", 0L);
    }

    /**
     * SP-13 / Test 3.
     * RuntimeException из onStart изолируется: pipeline не прерывается, следующий
     * делегат вызывается, счётчик ошибок неудавшегося делегата инкрементируется.
     */
    @Test
    void isolatesRuntimeExceptionFromOnStartAndContinuesPipeline() {
        AtomicInteger secondDelegateCalls = new AtomicInteger();

        SpanProcessor faultyOnStart = identified("test_anonymous_start_failure", new SpanProcessor() {
            @Override public void onStart(Context parentContext, ReadWriteSpan span) {
                throw new RuntimeException("sp-13-onstart-fault");
            }
            @Override public boolean isStartRequired() { return true; }
            @Override public void onEnd(ReadableSpan span) { }
            @Override public boolean isEndRequired() { return false; }
        });
        SpanProcessor okOnStart = identified("test_anonymous_start_ok", new SpanProcessor() {
            @Override public void onStart(Context parentContext, ReadWriteSpan span) {
                secondDelegateCalls.incrementAndGet();
            }
            @Override public boolean isStartRequired() { return true; }
            @Override public void onEnd(ReadableSpan span) { }
            @Override public boolean isEndRequired() { return false; }
        });

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

        SpanProcessor okOnEnd = identified("test_on_end_ok", new SpanProcessor() {
            @Override public void onStart(Context parentContext, ReadWriteSpan span) { }
            @Override public boolean isStartRequired() { return false; }
            @Override public void onEnd(ReadableSpan span) { secondDelegateCalls.incrementAndGet(); }
            @Override public boolean isEndRequired() { return true; }
        });

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
                .containsEntry("test_on_end_failure", 1L);
    }

    // -- Именованные тестовые делегаты для SP-13 -----------------------------
    // Именованные inner-классы дают стабильный Class.getSimpleName() → стабильные ключи
    // в errorCounts-карте PlatformCompositeSpanProcessor.

    /** Делегат-ExtendedSpanProcessor, бросающий RuntimeException из onEnding. */
    private static final class OnEndingThrowsRuntime implements ExtendedSpanProcessor, ProcessorDiagnosticIdentity {
        @Override public String diagnosticId() { return "test_on_ending_failure"; }
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
    private static final class RecordingOnEndingProcessor implements ExtendedSpanProcessor, ProcessorDiagnosticIdentity {
        private final AtomicInteger counter;
        RecordingOnEndingProcessor(AtomicInteger counter) { this.counter = counter; }
        @Override public String diagnosticId() { return "test_recording"; }
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
    private static final class OnEndThrowsRuntime implements SpanProcessor, ProcessorDiagnosticIdentity {
        @Override public String diagnosticId() { return "test_on_end_failure"; }
        @Override public void onStart(Context parentContext, ReadWriteSpan span) { }
        @Override public boolean isStartRequired() { return false; }
        @Override public void onEnd(ReadableSpan span) {
            throw new RuntimeException("sp-13-runtime-fault-in-onEnd");
        }
        @Override public boolean isEndRequired() { return true; }
    }

    // -------------------------------------------------------------------------

    private static final class NamedFaultyProcessor implements SpanProcessor, ProcessorDiagnosticIdentity {

        @SuppressWarnings("unused") // используется только для контекста имени делегата при возможном расширении
        private final String label;

        NamedFaultyProcessor(String label) {
            this.label = label;
        }

        @Override
        public String diagnosticId() {
            return "test_faulty";
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

    private static final class MutablePredicateProcessor implements ExtendedSpanProcessor, ProcessorDiagnosticIdentity {
        private final String diagnosticId;
        private volatile boolean startRequired = true;
        private volatile boolean endRequired = true;
        private volatile boolean onEndingRequired = true;
        private final AtomicInteger onStartCalls = new AtomicInteger();
        private final AtomicInteger onEndCalls = new AtomicInteger();
        private final AtomicInteger onEndingCalls = new AtomicInteger();

        MutablePredicateProcessor(String diagnosticId) {
            this.diagnosticId = diagnosticId;
        }

        @Override public String diagnosticId() { return diagnosticId; }
        @Override public void onStart(Context parentContext, ReadWriteSpan span) { onStartCalls.incrementAndGet(); }
        @Override public boolean isStartRequired() { return startRequired; }
        @Override public void onEnd(ReadableSpan span) { onEndCalls.incrementAndGet(); }
        @Override public boolean isEndRequired() { return endRequired; }
        @Override public void onEnding(ReadWriteSpan span) { onEndingCalls.incrementAndGet(); }
        @Override public boolean isOnEndingRequired() { return onEndingRequired; }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode forceFlush() { return CompletableResultCode.ofSuccess(); }
    }

    private static SpanProcessor identified(String diagnosticId, SpanProcessor delegate) {
        return new IdentifiedSpanProcessor(diagnosticId, delegate);
    }

    private static ExtendedSpanProcessor identifiedExtended(
            String diagnosticId,
            ExtendedSpanProcessor delegate
    ) {
        return new IdentifiedExtendedSpanProcessor(diagnosticId, delegate);
    }

    private record IdentifiedSpanProcessor(
            String diagnosticId,
            SpanProcessor delegate
    ) implements SpanProcessor, ProcessorDiagnosticIdentity {
        @Override public void onStart(Context parentContext, ReadWriteSpan span) { delegate.onStart(parentContext, span); }
        @Override public boolean isStartRequired() { return delegate.isStartRequired(); }
        @Override public void onEnd(ReadableSpan span) { delegate.onEnd(span); }
        @Override public boolean isEndRequired() { return delegate.isEndRequired(); }
        @Override public CompletableResultCode shutdown() { return delegate.shutdown(); }
        @Override public CompletableResultCode forceFlush() { return delegate.forceFlush(); }
    }

    private record IdentifiedExtendedSpanProcessor(
            String diagnosticId,
            ExtendedSpanProcessor delegate
    ) implements ExtendedSpanProcessor, ProcessorDiagnosticIdentity {
        @Override public void onEnding(ReadWriteSpan span) { delegate.onEnding(span); }
        @Override public boolean isOnEndingRequired() { return delegate.isOnEndingRequired(); }
        @Override public void onStart(Context parentContext, ReadWriteSpan span) { delegate.onStart(parentContext, span); }
        @Override public boolean isStartRequired() { return delegate.isStartRequired(); }
        @Override public void onEnd(ReadableSpan span) { delegate.onEnd(span); }
        @Override public boolean isEndRequired() { return delegate.isEndRequired(); }
        @Override public CompletableResultCode shutdown() { return delegate.shutdown(); }
        @Override public CompletableResultCode forceFlush() { return delegate.forceFlush(); }
    }
}
