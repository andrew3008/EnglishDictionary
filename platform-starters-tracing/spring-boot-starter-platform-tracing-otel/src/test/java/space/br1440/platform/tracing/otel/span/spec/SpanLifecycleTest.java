package space.br1440.platform.tracing.otel.span.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;
import space.br1440.platform.tracing.otel.exception.ExceptionRecordingPolicy;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.runtime.RecordingTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.SpanHandleImpl;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntimeFactory;
import space.br1440.platform.tracing.otel.runtime.otel.scope.OwningSpanScope;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class SpanLifecycleTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private DefaultTraceOperations tracing;
    private RecordingTracingRuntime recording;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracing = new DefaultTraceOperations(OtelTracingRuntimeFactory.create(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()));
        recording = new RecordingTracingRuntime();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    void run_closesSpanOnSuccess() {
        tracing.spans().operation("success").run(() -> {
        });
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        assertThat(exporter.getFinishedSpanItems().getFirst().getName()).isEqualTo("success");
    }

    @Test
    void run_recordsExceptionExactlyOnceAndRethrows() {
        IllegalStateException error = new IllegalStateException("boom");
        assertThatThrownBy(() ->
                tracing.spans().operation("failed").run(() -> {
                    throw error;
                }))
                .isSameAs(error);

        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getEvents()).hasSize(1);
        assertThat(span.getEvents().getFirst().getName()).isEqualTo("exception");
    }

    @Test
    void call_returnsValueAndClosesSpan() {
        String value = tracing.spans().operation("call-op").call(() -> "ok");
        assertThat(value).isEqualTo("ok");
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void call_recordsRuntimeExceptionExactlyOnceAndRethrows() {
        RuntimeException error = new RuntimeException("call-fail");
        assertThatThrownBy(() ->
                tracing.spans().operation("call-fail").call(() -> {
                    throw error;
                }))
                .isSameAs(error);

        assertThat(exporter.getFinishedSpanItems().getFirst().getEvents()).hasSize(1);
    }

    @Test
    void callChecked_propagatesCheckedExceptionAndRecordsExactlyOnce() throws Exception {
        Exception error = new Exception("checked");
        assertThatThrownBy(() ->
                tracing.spans().operation("checked").callChecked(() -> {
                    throw error;
                }))
                .isSameAs(error);

        assertThat(exporter.getFinishedSpanItems().getFirst().getEvents()).hasSize(1);
    }

    @Test
    void explicitRecordException_isAllowed() {
        IllegalArgumentException error = new IllegalArgumentException("explicit");
        try (SpanHandle handle = tracing.spans().operation("explicit").start()) {
            handle.recordException(error);
        }

        assertThat(exporter.getFinishedSpanItems().getFirst().getEvents()).hasSize(1);
    }

    @Test
    void duplicateSameThrowable_notDoubleRecorded() {
        IllegalStateException error = new IllegalStateException("dup");
        try (SpanHandle handle = tracing.spans().operation("dup").start()) {
            handle.recordException(error);
            handle.recordException(error);
        }

        assertThat(exporter.getFinishedSpanItems().getFirst().getEvents()).hasSize(1);
    }

    @Test
    void scopedPath_duplicateSameThrowable_notDoubleRecorded() {
        IllegalStateException error = new IllegalStateException("scoped-dup");
        SpanHandle[] holder = new SpanHandle[1];
        assertThatThrownBy(() ->
                SpanLifecycle.run(() -> {
                    SpanHandle handle = tracing.spans().operation("scoped-dup").start();
                    holder[0] = handle;
                    return handle;
                }, () -> {
                    holder[0].recordException(error);
                    throw error;
                }))
                .isSameAs(error);

        assertThat(exporter.getFinishedSpanItems().getFirst().getEvents()).hasSize(1);
    }

    @Test
    void twoDifferentThrowables_bothRecorded() {
        RuntimeException first = new RuntimeException("first");
        IllegalStateException second = new IllegalStateException("second");
        try (SpanHandle handle = tracing.spans().operation("two-errors").start()) {
            handle.recordException(first);
            handle.recordException(second);
        }

        assertThat(exporter.getFinishedSpanItems().getFirst().getEvents()).hasSize(2);
    }

    @Test
    void nestedSpans_closeInLifoOrder() {
        tracing.spans().operation("outer").run(() ->
                tracing.spans().operation("inner").run(() -> {
                }));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).getName()).isEqualTo("inner");
        assertThat(spans.get(1).getName()).isEqualTo("outer");
    }

    @Test
    void terminalMethods_routeThroughTracingRuntime() {
        DefaultTraceOperations recordingTracing = new DefaultTraceOperations(recording);
        recordingTracing.spans().operation("routed").run(() -> {
        });
        assertThat(recording.receivedSpecs()).hasSize(1);
        assertThat(recording.receivedSpecs().getFirst().name()).isEqualTo("routed");
    }

    @Test
    void traceOperations_hasNoPublicExecuteMethod() {
        assertThat(Arrays.stream(TraceOperations.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .noneMatch("execute"::equals))
                .isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "run, MESSAGE",
            "run, CAUSE",
            "run, STACK",
            "call, MESSAGE",
            "call, CAUSE",
            "call, STACK",
            "callChecked, MESSAGE",
            "callChecked, CAUSE",
            "callChecked, STACK"
    })
    void failingExceptionGetter_preservesOriginalFailureAndClosesOnce(String operation, FailingGetter getter) {
        // given
        BrokenGetterException failure = new BrokenGetterException(getter);
        SpanContext previousContext = Span.current().getSpanContext();
        Span span = tracerProvider.get("test").spanBuilder("broken-getter").startSpan();
        Scope activeScope = span.makeCurrent();
        AtomicInteger scopeCloses = new AtomicInteger();
        OwningSpanScope owning = new OwningSpanScope(span, () -> {
            scopeCloses.incrementAndGet();
            activeScope.close();
        }, new ExceptionRecorder(new ExceptionRecordingPolicy(true, true)));
        SpanHandle handle = SpanHandleImpl.wrap(owning);

        // when
        Throwable thrown = catchThrowable(() -> {
            switch (operation) {
                case "run" -> SpanLifecycle.run(() -> handle, () -> {
                    throw failure;
                });
                case "call" -> SpanLifecycle.call(() -> handle, () -> {
                    throw failure;
                });
                case "callChecked" -> SpanLifecycle.callChecked(() -> handle, () -> {
                    throw failure;
                });
                default -> throw new IllegalArgumentException("Unknown operation: " + operation);
            }
        });

        // then
        assertThat(thrown).isSameAs(failure);
        assertThat(failure.getterInvoked).isTrue();
        assertThat(scopeCloses.get()).isEqualTo(1);
        assertThat(span.isRecording()).isFalse();
        assertThat(Span.current().getSpanContext()).isEqualTo(previousContext);
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(data.getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isEqualTo(SpanResult.FAILURE.value());
        assertThat(data.getAttributes().get(SemconvKeys.ERROR_TYPE)).isEqualTo(failure.getClass().getName());
        assertThat(data.getEvents()).hasSize(1);
        assertThat(data.getEvents().getFirst().getName()).isEqualTo("exception");
        assertThat(data.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_TYPE))
                .isEqualTo(failure.getClass().getName());
    }

    private enum FailingGetter {
        MESSAGE,
        CAUSE,
        STACK
    }

    private static final class BrokenGetterException extends IllegalStateException {

        private final FailingGetter getter;
        private boolean getterInvoked;

        private BrokenGetterException(FailingGetter getter) {
            this.getter = getter;
        }

        @Override
        public String getMessage() {
            failIfSelected(FailingGetter.MESSAGE);
            return "Application failure";
        }

        @Override
        public synchronized Throwable getCause() {
            failIfSelected(FailingGetter.CAUSE);
            return null;
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            failIfSelected(FailingGetter.STACK);
            return new StackTraceElement[] {
                    new StackTraceElement("com.example.Service", "execute", "Service.java", 10)
            };
        }

        private void failIfSelected(FailingGetter invoked) {
            if (getter == invoked) {
                getterInvoked = true;
                throw new UnsupportedOperationException("Exception getter failed");
            }
        }
    }
}
