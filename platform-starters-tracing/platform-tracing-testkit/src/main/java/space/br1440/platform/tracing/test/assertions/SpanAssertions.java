package space.br1440.platform.tracing.test.assertions;

import io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions;
import io.opentelemetry.sdk.testing.assertj.SpanDataAssert;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import lombok.experimental.UtilityClass;
import org.assertj.core.api.Assertions;

import java.util.List;

@UtilityClass
public final class SpanAssertions {

    public static SpanData onlyFinishedSpan(InMemorySpanExporter exporter) {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        Assertions.assertThat(spans)
                .as("expected exactly one finished span in InMemorySpanExporter")
                .hasSize(1);
        return spans.getFirst();
    }

    public static SpanDataAssert assertOnlyFinishedSpan(InMemorySpanExporter exporter) {
        return OpenTelemetryAssertions.assertThat(onlyFinishedSpan(exporter));
    }

    public static SpanDataAssert assertSpanByName(InMemorySpanExporter exporter, String spanName) {
        List<SpanData> matches = exporter.getFinishedSpanItems().stream()
                .filter(s -> spanName.equals(s.getName()))
                .toList();

        Assertions.assertThat(matches)
                .as("expected exactly one span with name '%s'", spanName)
                .hasSize(1);

        return OpenTelemetryAssertions.assertThat(matches.getFirst());
    }
}
