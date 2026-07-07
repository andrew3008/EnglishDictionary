package space.br1440.platform.tracing.test;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import space.br1440.platform.tracing.api.PlatformTracing;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(PlatformTracingTestExtension.class)
class PlatformTracingTestExtensionTest {

    @Test
    void инжектит_PlatformTracing_и_InMemorySpanExporter(PlatformTracing tracing, InMemorySpanExporter exporter) {
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

        private PlatformTracing tracing;

        @BeforeEach
        void setUp(PlatformTracing tracing) {
            this.tracing = tracing;
        }

        @Test
        @ExtendWith(PlatformTracingTestExtension.class)
        void BeforeEach_получает_параметр_tracing(InMemorySpanExporter exporter) {
            assertThat(tracing).isNotNull();
            assertThat(exporter.getFinishedSpanItems()).isEmpty();
        }
    }
}
