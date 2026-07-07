package space.br1440.platform.tracing.test.junit;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет режим {@code SHARED_NESTED}: SDK один на класс, {@code reset()} не вызывается,
 * span'ы накапливаются между методами. Используется только с {@code @TestMethodOrder}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OtelSdkExtensionSharedScopeTest {

    @RegisterExtension
    static OtelSdkExtension otel = OtelSdkExtension.sharedAcrossNested();

    @Test
    @Order(1)
    void шаг1_создаёт_span(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
        Tracer tracer = sdk.getTracer("saga");
        Span span = tracer.spanBuilder("step-1").startSpan();
        span.end();
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    @Order(2)
    void шаг2_видит_span_шага1_и_добавляет_свой(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        // SHARED_NESTED: reset не вызывался, span шага 1 остался в exporter.
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);

        Tracer tracer = sdk.getTracer("saga");
        Span span = tracer.spanBuilder("step-2").startSpan();
        span.end();
        assertThat(exporter.getFinishedSpanItems()).hasSize(2);
    }
}
