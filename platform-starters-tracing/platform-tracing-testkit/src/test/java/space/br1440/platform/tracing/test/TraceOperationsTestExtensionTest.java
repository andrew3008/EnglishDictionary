package space.br1440.platform.tracing.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;

@ExtendWith(TraceOperationsTestExtension.class)
class TraceOperationsTestExtensionTest {

    @Test
    void injects_TraceOperations_and_InMemorySpanExporter(TraceOperations tracing, InMemorySpanExporter exporter) {
        assertThat(tracing).isNotNull();
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void injects_OpenTelemetry_and_OpenTelemetrySdk(OpenTelemetry openTelemetry, OpenTelemetrySdk sdk) {
        assertThat(openTelemetry).isSameAs(sdk);
    }

    @Test
    void span_written_in_test_lands_in_exporter(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        Tracer tracer = sdk.getTracer("test");
        Span span = tracer.spanBuilder("op").startSpan();
        span.end();
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"Connection refused, Connection refused", "password=testkit-secret-canary, <redacted>"})
    void facadeUsesSanitizedMessageDefault(
            String message, String expected, TraceOperations tracing, InMemorySpanExporter exporter) {
        // given
        IllegalStateException failure = new IllegalStateException(message);

        // when
        try (SpanHandle handle = tracing.spans().operation("failure").start()) {
            handle.recordException(failure);
        }

        // then
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getStatus().getDescription()).isEqualTo(expected);
        assertThat(span.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE)).isEqualTo(expected);
        assertThat(span.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE)).isNull();
    }

    static class BeforeEachParameterTest {

        private TraceOperations tracing;

        @BeforeEach
        void setUp(TraceOperations tracing) {
            this.tracing = tracing;
        }

        @Test
        @ExtendWith(TraceOperationsTestExtension.class)
        void BeforeEach_receives_tracing_parameter(InMemorySpanExporter exporter) {
            assertThat(tracing).isNotNull();
            assertThat(exporter.getFinishedSpanItems()).isEmpty();
        }
    }
}


