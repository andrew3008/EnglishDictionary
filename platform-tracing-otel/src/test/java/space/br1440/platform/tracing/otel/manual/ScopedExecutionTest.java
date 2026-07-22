package space.br1440.platform.tracing.otel.manual;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntimeFactory;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.runtime.RecordingTracingRuntime;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hard gate: scoped terminal methods and exactly-once exception recording.
 */
class ScopedExecutionTest {

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
                ScopedExecution.run(() -> {
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
}
