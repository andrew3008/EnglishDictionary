package space.br1440.platform.tracing.core;
import space.br1440.platform.tracing.core.runtime.otel.OtelTracingRuntimeFactory;
import space.br1440.platform.tracing.core.facade.DefaultTraceOperations;

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
import space.br1440.platform.tracing.api.span.SpanResult;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Тесты v3 {@code spans().operation(...).run/call/callChecked} scoped execution.
 */
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
    void run_создаёт_и_закрывает_span() {
        tracing.spans().operation("op").run(() ->
                assertThat(tracing.traceContext().spanId()).isPresent());

        assertThat(tracing.traceContext().spanId()).isEmpty();
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("op");
    }

    @Test
    void call_возвращает_значение() {
        String result = tracing.spans().operation("op").call(() -> "value");

        assertThat(result).isEqualTo("value");
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void run_при_RuntimeException_регистрирует_исключение_и_пробрасывает() {
        assertThatThrownBy(() -> tracing.spans().operation("op").run(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        SpanData failed = spans.get(0);
        assertThat(failed.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(failed.getAttributes().asMap().toString())
                .contains(SpanResult.FAILURE.value());
        assertThat(failed.getEvents()).extracting("name").contains("exception");
    }

    @Test
    void callChecked_пробрасывает_checked_исключение() {
        assertThatExceptionOfType(IOException.class).isThrownBy(() ->
                tracing.spans().operation("io-op").callChecked(() -> {
                    throw new IOException("io failure");
                })).withMessage("io failure");

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void callChecked_lambda_compatible_с_checked_exception() throws Exception {
        String value = tracing.spans().operation("io-op").callChecked(this::readSimulated);

        assertThat(value).isEqualTo("payload");
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void run_формирует_дочерний_span_внутри_родительского() {
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
    void run_root_создаёт_новую_трассу() {
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
    void run_восстанавливает_current_span_родителя_после_закрытия_child() {
        tracing.spans().operation("parent").run(() -> {
            String parentSpanId = Span.current().getSpanContext().getSpanId();
            tracing.spans().operation("child").run(() ->
                    assertThat(Span.current().getSpanContext().getSpanId()).isNotEqualTo(parentSpanId));
            assertThat(Span.current().getSpanContext().getSpanId()).isEqualTo(parentSpanId);
        });

        assertThat(exporter.getFinishedSpanItems()).hasSize(2);
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
