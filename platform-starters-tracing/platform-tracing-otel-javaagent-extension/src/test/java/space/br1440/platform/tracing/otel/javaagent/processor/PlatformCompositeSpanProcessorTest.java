package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.internal.ExtendedSpanProcessor;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class PlatformCompositeSpanProcessorTest {

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    @Test
    @SuppressWarnings("resource")
    void rejectsMissingBlankAndDuplicateDiagnosticIdentity() {

        // given
        @SuppressWarnings("resource")
        SpanProcessor missing = new SpanProcessor() {
            @Override
            public void onStart(Context parentContext, ReadWriteSpan span) {
            }

            @Override
            public boolean isStartRequired() {
                return false;
            }

            @Override
            public void onEnd(ReadableSpan span) {
            }

            @Override
            public boolean isEndRequired() {
                return false;
            }
        };

        // given/when
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
        // given/when/then
        try (PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of())) {
            assertThat(composite.isStartRequired()).isFalse();
            assertThat(composite.isEndRequired()).isFalse();
            assertThat(composite.isOnEndingRequired()).isFalse();
            assertThat(composite.shutdown().isSuccess()).isTrue();
            assertThat(composite.forceFlush().isSuccess()).isTrue();
        }
    }

    @Test
    void exception_in_one_delegate_does_not_stop_others_and_increments_counter() {
        // given
        AtomicInteger okCalls = new AtomicInteger();

        SpanProcessor faulty = new NamedFaultyProcessor("faulty-onstart");
        SpanProcessor ok = identified("test_ok_start", new SpanProcessor() {
            @Override
            public void onStart(Context parentContext, ReadWriteSpan span) {
                okCalls.incrementAndGet();
            }

            @Override
            public boolean isStartRequired() {
                return true;
            }

            @Override
            public void onEnd(ReadableSpan span) {
            }

            @Override
            public boolean isEndRequired() {
                return false;
            }
        });

        try (PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(faulty, ok))) {
            try (SpanProcessorHarness h = SpanProcessorHarness.of(composite)) {
                Tracer tracer = h.tracer("t");
                Span span = tracer.spanBuilder("op").startSpan();
                // when
                span.end();

                // then
                assertThat(h.exporter().getFinishedSpanItems()).hasSize(1);
            }

            assertThat(okCalls.get()).isEqualTo(1);
            assertThat(composite.getProcessorErrorCounts())
                    .containsEntry("test_faulty", 1L);
            assertThat(logs.events())
                    .filteredOn(event -> "COMPOSITE_PROCESSOR_CALLBACK_FAILED".equals(event.eventName()))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.fields())
                                .containsEntry("phase", "onStart")
                                .containsEntry("delegate", "test_faulty");
                        assertThat(event.throwableDigest()).contains(RuntimeException.class.getName());
                    });
            assertThat(logs.rendered()).doesNotContain("simulated onStart failure");
        }
    }

    @Test
    void onEnding_is_called_only_for_ExtendedSpanProcessor() {
        // given
        AtomicInteger onEndingCalls = new AtomicInteger();

        SpanProcessor plain = identified("test_plain", new SpanProcessor() {
            @Override
            public void onStart(Context parentContext, ReadWriteSpan span) {
            }

            @Override
            public boolean isStartRequired() {
                return true;
            }

            @Override
            public void onEnd(ReadableSpan span) {
            }

            @Override
            public boolean isEndRequired() {
                return false;
            }
        });

        ExtendedSpanProcessor extended = new IdentifiedExtendedSpanProcessor("test_extended", new ExtendedSpanProcessor() {
            @Override
            public void onEnding(ReadWriteSpan span) {
                onEndingCalls.incrementAndGet();
            }

            @Override
            public boolean isOnEndingRequired() {
                return true;
            }

            @Override
            public void onStart(Context parentContext, ReadWriteSpan span) {
            }

            @Override
            public boolean isStartRequired() {
                return false;
            }

            @Override
            public void onEnd(ReadableSpan span) {
            }

            @Override
            public boolean isEndRequired() {
                return false;
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        });

        try (PlatformCompositeSpanProcessor composite =
                     new PlatformCompositeSpanProcessor(List.of(plain, extended))) {

            // then
            assertThat(composite.isOnEndingRequired()).isTrue();

            try (SpanProcessorHarness h = SpanProcessorHarness.of(composite)) {
                Tracer tracer = h.tracer("t");
                tracer.spanBuilder("op").startSpan().end();
            }

            assertThat(onEndingCalls.get()).isEqualTo(1);
        }
    }

    @Test
    void phaseParticipationIsSnapshotAtConstructionAndIgnoresLaterPredicateChanges() {
        // given
        MutablePredicateProcessor mutable = new MutablePredicateProcessor("mutable_predicate");

        try (PlatformCompositeSpanProcessor composite =
                     new PlatformCompositeSpanProcessor(List.of(mutable))) {

            mutable.startRequired = false;
            mutable.endRequired = false;
            mutable.onEndingRequired = false;

            try (SpanProcessorHarness h = SpanProcessorHarness.of(composite)) {
                // when
                h.tracer("t").spanBuilder("op").startSpan().end();
            }

            // then
            assertThat(mutable.onStartCalls.get()).isEqualTo(1);
            assertThat(mutable.onEndCalls.get()).isEqualTo(1);
            assertThat(mutable.onEndingCalls.get()).isEqualTo(1);
        }
    }

    @Test
    void getProcessorErrorCounts_returns_immutable_snapshot() {
        // given
        SpanProcessor faulty = new NamedFaultyProcessor("faulty");

        try (PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(faulty))) {

            // then
            assertThat(composite.getProcessorErrorCounts())
                    .containsEntry("test_faulty", 0L);

            Map<String, Long> snapshot = composite.getProcessorErrorCounts();
            org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                    () -> attemptMutation(snapshot));
        }
    }

    @Test
    void isolatesRuntimeExceptionFromOnEndingAndContinuesPipeline() {
        // given
        AtomicInteger secondDelegateCalls = new AtomicInteger();
        OnEndingThrowsRuntime faultyDelegate = new OnEndingThrowsRuntime();
        RecordingOnEndingProcessor recordingDelegate = new RecordingOnEndingProcessor(secondDelegateCalls);

        try (PlatformCompositeSpanProcessor composite =
                     new PlatformCompositeSpanProcessor(List.of(faultyDelegate, recordingDelegate))) {

            try (SpanProcessorHarness h = SpanProcessorHarness.of(composite)) {
                Tracer tracer = h.tracer("t");
                // when
                tracer.spanBuilder("op").startSpan().end();
            }

            // then
            assertThat(secondDelegateCalls.get()).isEqualTo(1);
            assertThat(composite.getProcessorErrorCounts())
                    .containsEntry("test_on_ending_failure", 1L);
            assertThat(composite.getProcessorErrorCounts())
                    .containsEntry("test_recording", 0L);
        }
    }

    @Test
    void validationViolationUsesValidatorDiagnosticsWithoutCompositeError() {
        // given
        AtomicInteger followingDelegateCalls = new AtomicInteger();
        ValidatingSpanProcessor validator = new ValidatingSpanProcessor(TracingSettingsSourceTestFixtures.boundValidation(AgentInstanceId.random(), SpanAttributeValidationLevel.ERROR));

        try (PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(
                validator,
                new RecordingOnEndingProcessor(followingDelegateCalls)))) {

            try (SpanProcessorHarness harness = SpanProcessorHarness.of(composite)) {
                harness.tracer("validation-gate")
                        .spanBuilder("intentional-invalid-span")
                        .startSpan()
                        // when
                        .end();

                // then
                assertThat(harness.exporter().getFinishedSpanItems()).hasSize(1);
                assertThat(harness.exporter().getFinishedSpanItems().getFirst().getAttributes()
                        .get(AttributeKey.stringKey("platform.validation.missing")))
                        .contains(PlatformAttributes.PLATFORM_TYPE, PlatformAttributes.PLATFORM_RESULT);
            }

            assertThat(validator.getValidationViolationsTotal()).isEqualTo(1L);
            assertThat(followingDelegateCalls.get()).isEqualTo(1);
            assertThat(composite.getProcessorErrorCounts())
                    .containsEntry("validation", 0L);
        }
    }

    @Test
    void validSpanDoesNotIncrementValidationViolationCounter() {
        // given
        ValidatingSpanProcessor validator = new ValidatingSpanProcessor(TracingSettingsSourceTestFixtures.boundValidation(AgentInstanceId.random(), SpanAttributeValidationLevel.ERROR));

        try (PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(validator))) {
            try (SpanProcessorHarness harness = SpanProcessorHarness.of(composite)) {
                harness.tracer("validation-gate")
                        .spanBuilder("valid-span")
                        .setAttribute(PlatformAttributes.PLATFORM_TYPE, "http_server")
                        .setAttribute(PlatformAttributes.PLATFORM_RESULT, "success")
                        .startSpan()
                        // when
                        .end();
                // then
                assertThat(harness.exporter().getFinishedSpanItems()).hasSize(1);
            }

            assertThat(validator.getValidationViolationsTotal()).isZero();
            assertThat(composite.getProcessorErrorCounts())
                    .containsEntry("validation", 0L);
        }
    }

    @Test
    void isolatesRuntimeExceptionFromOnStartAndContinuesPipeline() {
        // given
        AtomicInteger secondDelegateCalls = new AtomicInteger();

        SpanProcessor faultyOnStart = identified("test_anonymous_start_failure", new SpanProcessor() {
            @Override
            public void onStart(Context parentContext, ReadWriteSpan span) {
                throw new RuntimeException("sp-13-onstart-fault");
            }

            @Override
            public boolean isStartRequired() {
                return true;
            }

            @Override
            public void onEnd(ReadableSpan span) {
            }

            @Override
            public boolean isEndRequired() {
                return false;
            }
        });

        SpanProcessor okOnStart = identified("test_anonymous_start_ok", new SpanProcessor() {
            @Override
            public void onStart(Context parentContext, ReadWriteSpan span) {
                secondDelegateCalls.incrementAndGet();
            }

            @Override
            public boolean isStartRequired() {
                return true;
            }

            @Override
            public void onEnd(ReadableSpan span) {
            }

            @Override
            public boolean isEndRequired() {
                return false;
            }
        });

        try (PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(faultyOnStart, okOnStart))) {
            try (SpanProcessorHarness h = SpanProcessorHarness.of(composite)) {
                Tracer tracer = h.tracer("t");
                // when
                tracer.spanBuilder("op").startSpan().end();
            }

            // then
            assertThat(secondDelegateCalls.get()).isEqualTo(1);

            long totalOnStartErrors = composite.getProcessorErrorCounts().values().stream()
                    .mapToLong(Long::longValue)
                    .sum();
            assertThat(totalOnStartErrors).isGreaterThanOrEqualTo(1L);
        }
    }

    @Test
    void isolatesRuntimeExceptionFromOnEndAndContinuesPipeline() {
        // given
        AtomicInteger secondDelegateCalls = new AtomicInteger();
        OnEndThrowsRuntime faultyDelegate = new OnEndThrowsRuntime();

        SpanProcessor okOnEnd = identified("test_on_end_ok", new SpanProcessor() {
            @Override
            public void onStart(Context parentContext, ReadWriteSpan span) {
            }

            @Override
            public boolean isStartRequired() {
                return false;
            }

            @Override
            public void onEnd(ReadableSpan span) {
                secondDelegateCalls.incrementAndGet();
            }

            @Override
            public boolean isEndRequired() {
                return true;
            }
        });

        try (PlatformCompositeSpanProcessor composite =
                     new PlatformCompositeSpanProcessor(List.of(faultyDelegate, okOnEnd))) {

            try (SpanProcessorHarness h = SpanProcessorHarness.of(composite)) {
                Tracer tracer = h.tracer("t");
                // when
                tracer.spanBuilder("op").startSpan().end();
            }

            // then
            assertThat(secondDelegateCalls.get()).isEqualTo(1);
            assertThat(composite.getProcessorErrorCounts())
                    .containsEntry("test_on_end_failure", 1L);
        }
    }

    private static void attemptMutation(Map<String, Long> snapshot) {
        snapshot.put("hacked", 99L);
    }

    private static final class OnEndingThrowsRuntime implements ExtendedSpanProcessor, ProcessorDiagnosticIdentity {
        @Override
        public String diagnosticId() {
            return "test_on_ending_failure";
        }

        @Override
        public void onEnding(ReadWriteSpan span) {
            throw new RuntimeException("sp-13-runtime-fault-in-onEnding");
        }

        @Override
        public boolean isOnEndingRequired() {
            return true;
        }

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
        }

        @Override
        public boolean isStartRequired() {
            return false;
        }

        @Override
        public void onEnd(ReadableSpan span) {
        }

        @Override
        public boolean isEndRequired() {
            return false;
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    private record RecordingOnEndingProcessor(AtomicInteger counter)
            implements ExtendedSpanProcessor, ProcessorDiagnosticIdentity {

        @Override
        public String diagnosticId() {
            return "test_recording";
        }

        @Override
        public void onEnding(ReadWriteSpan span) {
            counter.incrementAndGet();
        }

        @Override
        public boolean isOnEndingRequired() {
            return true;
        }

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
        }

        @Override
        public boolean isStartRequired() {
            return false;
        }

        @Override
        public void onEnd(ReadableSpan span) {
        }

        @Override
        public boolean isEndRequired() {
            return false;
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    private static final class OnEndThrowsRuntime implements SpanProcessor, ProcessorDiagnosticIdentity {
        @Override
        public String diagnosticId() {
            return "test_on_end_failure";
        }

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
        }

        @Override
        public boolean isStartRequired() {
            return false;
        }

        @Override
        public void onEnd(ReadableSpan span) {
            throw new RuntimeException("sp-13-runtime-fault-in-onEnd");
        }

        @Override
        public boolean isEndRequired() {
            return true;
        }
    }

    private record NamedFaultyProcessor(@SuppressWarnings("unused") String label)
            implements SpanProcessor, ProcessorDiagnosticIdentity {

        private NamedFaultyProcessor(String label) {
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
        public boolean isStartRequired() {
            return true;
        }

        @Override
        public void onEnd(ReadableSpan span) {
        }

        @Override
        public boolean isEndRequired() {
            return false;
        }
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

        @Override
        public String diagnosticId() {
            return diagnosticId;
        }

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
            onStartCalls.incrementAndGet();
        }

        @Override
        public boolean isStartRequired() {
            return startRequired;
        }

        @Override
        public void onEnd(ReadableSpan span) {
            onEndCalls.incrementAndGet();
        }

        @Override
        public boolean isEndRequired() {
            return endRequired;
        }

        @Override
        public void onEnding(ReadWriteSpan span) {
            onEndingCalls.incrementAndGet();
        }

        @Override
        public boolean isOnEndingRequired() {
            return onEndingRequired;
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    private static SpanProcessor identified(String diagnosticId, SpanProcessor delegate) {
        return new IdentifiedSpanProcessor(diagnosticId, delegate);
    }

    private record IdentifiedSpanProcessor(String diagnosticId,
                                           SpanProcessor delegate) implements SpanProcessor, ProcessorDiagnosticIdentity {
        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
            delegate.onStart(parentContext, span);
        }

        @Override
        public boolean isStartRequired() {
            return delegate.isStartRequired();
        }

        @Override
        public void onEnd(ReadableSpan span) {
            delegate.onEnd(span);
        }

        @Override
        public boolean isEndRequired() {
            return delegate.isEndRequired();
        }

        @Override
        public CompletableResultCode shutdown() {
            return delegate.shutdown();
        }

        @Override
        public CompletableResultCode forceFlush() {
            return delegate.forceFlush();
        }
    }

    private record IdentifiedExtendedSpanProcessor(String diagnosticId,
                                                   ExtendedSpanProcessor delegate)
            implements ExtendedSpanProcessor, ProcessorDiagnosticIdentity {

        @Override
        public void onEnding(ReadWriteSpan span) {
            delegate.onEnding(span);
        }

        @Override
        public boolean isOnEndingRequired() {
            return delegate.isOnEndingRequired();
        }

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
            delegate.onStart(parentContext, span);
        }

        @Override
        public boolean isStartRequired() {
            return delegate.isStartRequired();
        }

        @Override
        public void onEnd(ReadableSpan span) {
            delegate.onEnd(span);
        }

        @Override
        public boolean isEndRequired() {
            return delegate.isEndRequired();
        }

        @Override
        public CompletableResultCode shutdown() {
            return delegate.shutdown();
        }

        @Override
        public CompletableResultCode forceFlush() {
            return delegate.forceFlush();
        }
    }
}
