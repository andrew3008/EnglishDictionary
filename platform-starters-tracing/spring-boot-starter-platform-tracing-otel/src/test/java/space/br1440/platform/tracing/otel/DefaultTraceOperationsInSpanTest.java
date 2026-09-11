package space.br1440.platform.tracing.otel;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntimeFactory;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.span.SpanResult;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultTraceOperationsInSpanTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private TraceOperations tracing;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracing = new DefaultTraceOperations(OtelTracingRuntimeFactory.create(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()));
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    void run_creates_and_closes_span() {
        tracing.spans().operation("op").run(() ->
                assertThat(tracing.traceContext().spanId()).isPresent());

        assertThat(tracing.traceContext().spanId()).isEmpty();
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.getFirst().getName()).isEqualTo("op");
    }

    @Test
    void call_returns_value() {
        String result = tracing.spans().operation("op").call(() -> "value");

        assertThat(result).isEqualTo("value");
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void run_on_RuntimeException_records_exception_and_rethrows() {
        assertThatThrownBy(() -> tracing.spans().operation("op").run(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        SpanData failed = spans.getFirst();
        assertThat(failed.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(failed.getAttributes().asMap().toString())
                .contains(SpanResult.FAILURE.value());
        assertThat(failed.getEvents()).extracting("name").contains("exception");
    }

    @Test
    void callChecked_rethrows_checked_exception() {
        assertThatExceptionOfType(IOException.class).isThrownBy(() ->
                tracing.spans().operation("io-op").callChecked(() -> {
                    throw new IOException("io failure");
                })).withMessage("io failure");

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.getFirst().getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void callChecked_lambda_compatible_with_checked_exception() throws Exception {
        String value = tracing.spans().operation("io-op").callChecked(this::readSimulated);

        assertThat(value).isEqualTo("payload");
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void run_creates_child_span_inside_parent() {
        tracing.spans().operation("parent").run(() -> {
            String parentTraceId = tracing.traceContext().traceId().orElseThrow();
            tracing.spans().operation("child").run(() ->
                    assertThat(tracing.traceContext().traceId()).hasValue(parentTraceId));
        });

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);
        SpanData parent = findByName(spans, "parent");
        SpanData child = findByName(spans, "child");
        assertThat(child.getTraceId()).isEqualTo(parent.getTraceId());
        assertThat(child.getParentSpanId()).isEqualTo(parent.getSpanId());
    }

    @Test
    void run_root_creates_new_trace() {
        tracing.spans().operation("outer").run(() -> {
            String outerTrace = tracing.traceContext().traceId().orElseThrow();
            tracing.spans().operation("scheduled").root().run(() -> {
                assertThat(tracing.traceContext().traceId()).isPresent();
                assertThat(tracing.traceContext().traceId()).isNotEqualTo(Optional.of(outerTrace));
            });
        });

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);
        SpanData root = findByName(spans, "scheduled");
        SpanData outer = findByName(spans, "outer");
        assertThat(root.getTraceId()).isNotEqualTo(outer.getTraceId());
    }

    @Test
    void run_restores_parent_current_span_after_child_close() {
        tracing.spans().operation("parent").run(() -> {
            String parentSpanId = Span.current().getSpanContext().getSpanId();
            tracing.spans().operation("child").run(() ->
                    assertThat(Span.current().getSpanContext().getSpanId()).isNotEqualTo(parentSpanId));
            assertThat(Span.current().getSpanContext().getSpanId()).isEqualTo(parentSpanId);
        });

        assertThat(exporter.getFinishedSpanItems()).hasSize(2);
    }

    @Test
    void traceparent_liveView_reflectsCurrentScopeThroughPublicView() {
        // given: view получен до открытия scope
        ActiveTraceContextView view = tracing.traceContext();
        assertThat(view.traceparent()).isEmpty();

        tracing.spans().operation("outer").run(() -> {
            // when: активен scope A
            Optional<String> valueA = view.traceparent();
            // then
            assertThat(valueA).isPresent();

            tracing.spans().operation("inner").root().run(() -> {
                // when: активен вложенный scope B
                // then
                assertThat(view.traceparent()).isPresent().isNotEqualTo(valueA);
            });

            // then: после закрытия B снова видно значение A
            assertThat(view.traceparent()).isEqualTo(valueA);
        });

        // then: после закрытия A — пусто
        assertThat(view.traceparent()).isEmpty();
    }

    private String readSimulated() throws IOException {
        return "payload";
    }

    private static SpanData findByName(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Span '" + name + "' not found"));
    }
}
