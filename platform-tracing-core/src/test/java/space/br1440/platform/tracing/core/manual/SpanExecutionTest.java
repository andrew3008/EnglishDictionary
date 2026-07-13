package space.br1440.platform.tracing.core.manual;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.core.runtime.otel.OtelTracingRuntimeFactory;
import space.br1440.platform.tracing.core.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.core.runtime.RecordingTracingRuntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 4 hard gate: {@code manual().spanFromSpec(...)} terminal lifecycle.
 */
class SpanExecutionTest {

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
        SpanSpec spec = governedSpec("spec-run");
        tracing.manual().spanFromSpec(spec).run(() -> {
        });
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        assertThat(exporter.getFinishedSpanItems().getFirst().getName()).isEqualTo("spec-run");
    }

    @Test
    void run_recordsExceptionExactlyOnceAndRethrows() {
        SpanSpec spec = governedSpec("spec-fail");
        IllegalStateException error = new IllegalStateException("spec-boom");
        assertThatThrownBy(() ->
                tracing.manual().spanFromSpec(spec).run(() -> {
                    throw error;
                }))
                .isSameAs(error);

        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getEvents()).hasSize(1);
    }

    @Test
    void call_returnsValueAndClosesSpan() {
        SpanSpec spec = governedSpec("spec-call");
        String value = tracing.manual().spanFromSpec(spec).call(() -> "value");
        assertThat(value).isEqualTo("value");
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void callChecked_propagatesCheckedExceptionAndRecordsExactlyOnce() throws Exception {
        SpanSpec spec = governedSpec("spec-checked");
        Exception error = new Exception("checked-spec");
        assertThatThrownBy(() ->
                tracing.manual().spanFromSpec(spec).callChecked(() -> {
                    throw error;
                }))
                .isSameAs(error);

        assertThat(exporter.getFinishedSpanItems().getFirst().getEvents()).hasSize(1);
    }

    @Test
    void spanFromSpec_routesSameSpecThroughTracingRuntime() {
        SpanSpec spec = governedSpec("from-spec");
        DefaultTraceOperations recordingTracing = new DefaultTraceOperations(recording);
        recordingTracing.manual().spanFromSpec(spec).run(() -> {
        });
        assertThat(recording.receivedSpecs()).containsExactly(spec);
    }

    private static SpanSpec governedSpec(String name) {
        return SpanSpec.builder(name)
                .category(SpanCategory.INTERNAL)
                .child()
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                .build();
    }
}
