package space.br1440.platform.tracing.otel.javaagent.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntimeTestFixtures;

/**
 * Проверяет safe export path и отсутствие повторного replacement-aware wrapping.
 */
class PlatformExportProcessorFactorySafeWrapTest {

    @Test
    @DisplayName("captureExporter сохраняет safe export path за replacement-aware границей")
    void captureExporterKeepsSafeExportPathBehindReplacementBoundary() {
        // given
        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(
                null, ScrubbingRuntimeTestFixtures.disabled());
        CountingExporter delegate = new CountingExporter();

        // when
        SpanExporter result = factory.captureExporter(delegate);
        result.export(java.util.List.of()).join(1, java.util.concurrent.TimeUnit.SECONDS);

        // then
        assertThat(delegate.exportCalls).hasValue(1);
        assertThat(result).isNotInstanceOf(SafeSpanExporter.class);
    }

    @Test
    @DisplayName("Повторный проход не создаёт вторую replacement-aware границу")
    void captureExporterDoesNotDoubleWrap() {
        // given
        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(
                null, ScrubbingRuntimeTestFixtures.disabled());

        // when
        SpanExporter once = factory.captureExporter(SpanExporter.noop());
        SpanExporter twice = factory.captureExporter(once);

        // then
        assertThat(twice).isSameAs(once);
    }

    private static final class CountingExporter implements SpanExporter {
        private final AtomicInteger exportCalls = new AtomicInteger();

        @Override public CompletableResultCode export(Collection<SpanData> spans) {
            exportCalls.incrementAndGet();
            return CompletableResultCode.ofSuccess();
        }
        @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }

}
