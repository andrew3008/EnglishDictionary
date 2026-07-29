package space.br1440.platform.tracing.test.assertions;

import io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions;
import io.opentelemetry.sdk.testing.assertj.SpanDataAssert;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import lombok.experimental.UtilityClass;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.ListAssert;

import java.util.List;

/**
 * Static assertion helpers for {@link SpanData} and {@link InMemorySpanExporter}.
 *
 * <p>Delegates all span-level assertions to
 * {@link OpenTelemetryAssertions#assertThat(SpanData)} from
 * {@code opentelemetry-sdk-testing} — no custom fluent API is introduced
 * (plan v2 §3, principle #3).
 *
 * <h3>Preferred usage with {@code SpanProcessorHarness} / {@code OtelSdkExtension}</h3>
 * <pre>{@code
 * // assert a single named span from the harness span list
 * SpanAssertions.assertSpan(h.spans(), "server-op")
 *         .hasAttribute(stringKey("platform.type"), "http_server");
 *
 * // assert the full list
 * SpanAssertions.assertSpans(h.spans()).hasSize(2);
 * }</pre>
 *
 * <h3>Legacy usage with {@code InMemorySpanExporter} (retained for compatibility)</h3>
 * <pre>{@code
 * SpanAssertions.assertOnlyFinishedSpan(exporter)
 *         .hasAttribute(stringKey("platform.result"), "success");
 * }</pre>
 *
 * @since 5.5
 */
@UtilityClass
public final class SpanAssertions {

    // =========================================================================
    // List<SpanData> — primary API (plan §5.5)
    // =========================================================================

    /**
     * Finds the single span with the given name in {@code spans} and returns a
     * fluent {@link SpanDataAssert} for further assertions.
     *
     * <p>Fails with a descriptive message if zero or more than one span with
     * that name is found, listing all available span names.
     *
     * @param spans    finished span list, e.g. from {@code SpanProcessorHarness.spans()}
     * @param spanName exact span name to look up
     * @return {@link SpanDataAssert} for the matched span
     * @throws AssertionError if no span or more than one span matches
     */
    public static SpanDataAssert assertSpan(List<SpanData> spans, String spanName) {
        List<SpanData> matches = spans.stream()
                .filter(s -> spanName.equals(s.getName()))
                .toList();

        Assertions.assertThat(matches)
                .as("Expected exactly one span named '%s', but found %d. " +
                    "Available span names: %s",
                    spanName,
                    matches.size(),
                    spans.stream().map(SpanData::getName).toList())
                .hasSize(1);

        return OpenTelemetryAssertions.assertThat(matches.getFirst());
    }

    /**
     * Returns an AssertJ {@link ListAssert} over the full span list for
     * size/order/presence assertions.
     *
     * <pre>{@code
     * SpanAssertions.assertSpans(h.spans()).hasSize(3);
     * SpanAssertions.assertSpans(h.spans())
     *         .extracting(SpanData::getName)
     *         .containsExactly("a", "b", "c");
     * }</pre>
     *
     * @param spans finished span list
     * @return AssertJ list assertion
     */
    public static ListAssert<SpanData> assertSpans(List<SpanData> spans) {
        return Assertions.assertThat(spans);
    }

    /**
     * Asserts that {@code spans} contains exactly one element and returns
     * a {@link SpanDataAssert} for it.
     *
     * @param spans finished span list
     * @return {@link SpanDataAssert} for the single span
     * @throws AssertionError if the list does not contain exactly one span
     */
    public static SpanDataAssert assertOnlySpan(List<SpanData> spans) {
        Assertions.assertThat(spans)
                .as("Expected exactly one finished span, but found %d", spans.size())
                .hasSize(1);
        return OpenTelemetryAssertions.assertThat(spans.getFirst());
    }

    // =========================================================================
    // InMemorySpanExporter — legacy API (retained, no removals)
    // =========================================================================

    /**
     * Asserts that {@code exporter} contains exactly one finished span and
     * returns it as a raw {@link SpanData}.
     *
     * @param exporter the in-memory exporter to inspect
     * @return the single finished span
     * @throws AssertionError if the exporter does not contain exactly one span
     */
    public static SpanData onlyFinishedSpan(InMemorySpanExporter exporter) {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        Assertions.assertThat(spans)
                .as("expected exactly one finished span in InMemorySpanExporter")
                .hasSize(1);
        return spans.getFirst();
    }

    /**
     * Asserts that {@code exporter} contains exactly one finished span and
     * returns a fluent {@link SpanDataAssert} for it.
     *
     * @param exporter the in-memory exporter to inspect
     * @return {@link SpanDataAssert} for the single span
     * @throws AssertionError if the exporter does not contain exactly one span
     */
    public static SpanDataAssert assertOnlyFinishedSpan(InMemorySpanExporter exporter) {
        return OpenTelemetryAssertions.assertThat(onlyFinishedSpan(exporter));
    }

    /**
     * Finds the single span with {@code spanName} in {@code exporter} and
     * returns a fluent {@link SpanDataAssert} for it.
     *
     * @param exporter the in-memory exporter to inspect
     * @param spanName exact span name
     * @return {@link SpanDataAssert} for the matched span
     * @throws AssertionError if zero or more than one span matches
     */
    public static SpanDataAssert assertSpanByName(InMemorySpanExporter exporter,
                                                  String spanName) {
        return assertSpan(exporter.getFinishedSpanItems(), spanName);
    }
}
