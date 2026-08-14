package space.br1440.platform.tracing.test.junit;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что в режиме {@code METHOD} SDK пересоздаётся на каждый тест и
 * {@link InMemorySpanExporter} стартует пустым.
 */
class OtelSdkExtensionMethodScopeTest {

    @RegisterExtension
    static OtelSdkExtension otel = OtelSdkExtension.methodScope();

    @Test
    void первый_тест_пишет_span(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        Tracer tracer = sdk.getTracer("test");
        Span span = tracer.spanBuilder("op-1").startSpan();
        span.end();

        assertThat(exporter.getFinishedSpanItems())
                .hasSize(1)
                .extracting("name")
                .containsExactly("op-1");
    }

    @Test
    void второй_тест_видит_пустой_exporter(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        // SDK пересоздан, exporter — новый, span'ов от предыдущего теста нет.
        assertThat(exporter.getFinishedSpanItems()).isEmpty();

        Tracer tracer = sdk.getTracer("test");
        Span span = tracer.spanBuilder("op-2").startSpan();
        span.end();

        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    }
}
