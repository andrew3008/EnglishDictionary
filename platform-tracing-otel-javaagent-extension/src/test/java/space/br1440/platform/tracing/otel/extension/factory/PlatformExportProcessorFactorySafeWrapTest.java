package space.br1440.platform.tracing.otel.extension.factory;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.extension.exporter.SafeSpanExporter;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты обёртывания захваченного exporter'а в {@link SafeSpanExporter} фабрикой
 * {@link PlatformExportProcessorFactory#captureExporter}.
 */
class PlatformExportProcessorFactorySafeWrapTest {

    @Test
    @DisplayName("captureExporter оборачивает транспортный exporter в SafeSpanExporter")
    void captureExporterWrapsInSafe() {
        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(null);

        SpanExporter result = factory.captureExporter(new NoopExporter());

        assertThat(result).isInstanceOf(SafeSpanExporter.class);
    }

    @Test
    @DisplayName("Повторный проход не оборачивает SafeSpanExporter дважды")
    void captureExporterDoesNotDoubleWrap() {
        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(null);

        SpanExporter once = factory.captureExporter(new NoopExporter());
        SpanExporter twice = factory.captureExporter(once);

        assertThat(twice).isInstanceOf(SafeSpanExporter.class);
        // Не должно быть SafeSpanExporter(SafeSpanExporter(...)) — повторная обёртка запрещена.
        assertThat(twice).isSameAs(once);
    }

    private static final class NoopExporter implements SpanExporter {
        @Override public CompletableResultCode export(Collection<SpanData> spans) { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }
}
