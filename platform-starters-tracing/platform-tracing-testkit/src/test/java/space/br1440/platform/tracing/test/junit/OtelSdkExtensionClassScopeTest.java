package space.br1440.platform.tracing.test.junit;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет режим {@code CLASS}: SDK один на весь класс, {@code exporter.reset()}
 * вызывается перед каждым тестом, то есть каждый тест видит пустой exporter,
 * но идентичность {@link OpenTelemetrySdk} сохраняется.
 */
class OtelSdkExtensionClassScopeTest {

    @RegisterExtension
    static OtelSdkExtension otel = OtelSdkExtension.classScope();

    private static OpenTelemetrySdk firstSdk;
    private static OpenTelemetrySdk secondSdk;

    @Test
    void тест1(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        firstSdk = sdk;
        assertThat(exporter.getFinishedSpanItems()).isEmpty();

        Tracer tracer = sdk.getTracer("test");
        Span span = tracer.spanBuilder("op-a").startSpan();
        span.end();

        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void тест2(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        secondSdk = sdk;
        // Reset был выполнен между тестами — exporter пуст.
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @AfterAll
    static void проверяемЧтоSdkОдинАТотЖе() {
        assertThat(firstSdk).isSameAs(secondSdk);
    }
}
