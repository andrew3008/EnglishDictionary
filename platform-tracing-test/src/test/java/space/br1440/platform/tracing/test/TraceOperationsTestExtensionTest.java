package space.br1440.platform.tracing.test;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import space.br1440.platform.tracing.api.TraceOperations;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(TraceOperationsTestExtension.class)
class TraceOperationsTestExtensionTest {

    @Test
    void инжектит_TraceOperations_и_InMemorySpanExporter(TraceOperations tracing, InMemorySpanExporter exporter) {
        assertThat(tracing).isNotNull();
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void инжектит_OpenTelemetry_и_OpenTelemetrySdk(OpenTelemetry openTelemetry, OpenTelemetrySdk sdk) {
        assertThat(openTelemetry).isSameAs(sdk);
    }

    @Test
    void span_записанный_в_тесте_попадает_в_exporter(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        Tracer tracer = sdk.getTracer("test");
        Span span = tracer.spanBuilder("op").startSpan();
        span.end();
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    }

    // BeforeEach получает параметры через ParameterResolver — это основной сценарий использования.
    static class BeforeEachParameterTest {

        private TraceOperations tracing;

        @BeforeEach
        void setUp(TraceOperations tracing) {
            this.tracing = tracing;
        }

        @Test
        @ExtendWith(TraceOperationsTestExtension.class)
        void BeforeEach_получает_параметр_tracing(InMemorySpanExporter exporter) {
            assertThat(tracing).isNotNull();
            assertThat(exporter.getFinishedSpanItems()).isEmpty();
        }
    }
}


