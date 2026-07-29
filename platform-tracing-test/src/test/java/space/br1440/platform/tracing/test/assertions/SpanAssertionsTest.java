package space.br1440.platform.tracing.test.assertions;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.assertj.SpanDataAssert;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SpanAssertions}.
 * Coverage target: ≥95 % lines, ≥90 % branches (plan §8.5).
 */
class SpanAssertionsTest {

    private SpanProcessorHarness harness;

    @BeforeEach
    void setUp() {
        harness = SpanProcessorHarness.of(SpanProcessor.composite());
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // =========================================================================
    // assertSpan(List, name)
    // =========================================================================

    @Nested
    class AssertSpanByName {

        @Test
        void returns_assert_for_matching_span() {
            harness.tracer().spanBuilder("target").startSpan().end();
            SpanDataAssert a = SpanAssertions.assertSpan(harness.spans(), "target");
            assertThat(a).isNotNull();
        }

        @Test
        void fails_when_span_not_found() {
            harness.tracer().spanBuilder("other").startSpan().end();
            assertThatThrownBy(() ->
                    SpanAssertions.assertSpan(harness.spans(), "missing"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("missing")
                    .hasMessageContaining("other");
        }

        @Test
        void fails_when_empty_list() {
            assertThatThrownBy(() ->
                    SpanAssertions.assertSpan(List.of(), "any"))
                    .isInstanceOf(AssertionError.class);
        }

        @Test
        void fails_when_duplicate_names() {
            harness.tracer().spanBuilder("dup").startSpan().end();
            harness.tracer().spanBuilder("dup").startSpan().end();
            assertThatThrownBy(() ->
                    SpanAssertions.assertSpan(harness.spans(), "dup"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("dup");
        }

        @Test
        void works_with_multiple_spans_finds_correct_one() {
            harness.tracer().spanBuilder("a").startSpan().end();
            harness.tracer().spanBuilder("target").startSpan().end();
            harness.tracer().spanBuilder("b").startSpan().end();
            SpanAssertions.assertSpan(harness.spans(), "target");
        }

        @Test
        void returned_assert_supports_chaining() {
            harness.tracer().spanBuilder("op")
                    .setSpanKind(SpanKind.SERVER)
                    .startSpan().end();
            SpanAssertions.assertSpan(harness.spans(), "op")
                    .hasName("op")
                    .hasKind(SpanKind.SERVER);
        }
    }

    // =========================================================================
    // assertSpans(List)
    // =========================================================================

    @Nested
    class AssertSpansList {

        @Test
        void returns_list_assert() {
            harness.tracer().spanBuilder("a").startSpan().end();
            harness.tracer().spanBuilder("b").startSpan().end();
            SpanAssertions.assertSpans(harness.spans()).hasSize(2);
        }

        @Test
        void extracting_names_works() {
            harness.tracer().spanBuilder("x").startSpan().end();
            harness.tracer().spanBuilder("y").startSpan().end();
            SpanAssertions.assertSpans(harness.spans())
                    .extracting(SpanData::getName)
                    .containsExactlyInAnyOrder("x", "y");
        }
    }

    // =========================================================================
    // assertOnlySpan(List)
    // =========================================================================

    @Nested
    class AssertOnlySpan {

        @Test
        void passes_for_single_span() {
            harness.tracer().spanBuilder("solo").startSpan().end();
            SpanAssertions.assertOnlySpan(harness.spans()).hasName("solo");
        }

        @Test
        void fails_for_empty_list() {
            assertThatThrownBy(() -> SpanAssertions.assertOnlySpan(List.of()))
                    .isInstanceOf(AssertionError.class);
        }

        @Test
        void fails_for_two_spans() {
            harness.tracer().spanBuilder("a").startSpan().end();
            harness.tracer().spanBuilder("b").startSpan().end();
            assertThatThrownBy(() ->
                    SpanAssertions.assertOnlySpan(harness.spans()))
                    .isInstanceOf(AssertionError.class);
        }
    }

    // =========================================================================
    // Legacy InMemorySpanExporter API
    // =========================================================================

    @Nested
    class LegacyExporterApi {

        private InMemorySpanExporter exporter;
        private SpanProcessorHarness legacyHarness;

        @BeforeEach
        void setUp() {
            exporter = InMemorySpanExporter.create();
            // Use a separate harness that exposes the same exporter.
            // We drive spans through the outer harness and copy to this exporter
            // by simply using SpanAssertions against the outer harness exporter.
            legacyHarness = harness; // reuse outer harness exporter via harness.exporter()
        }

        @Test
        void onlyFinishedSpan_returns_span_data() {
            harness.tracer().spanBuilder("only").startSpan().end();
            SpanData span = SpanAssertions.onlyFinishedSpan(harness.exporter());
            assertThat(span.getName()).isEqualTo("only");
        }

        @Test
        void onlyFinishedSpan_fails_for_empty() {
            assertThatThrownBy(() -> SpanAssertions.onlyFinishedSpan(harness.exporter()))
                    .isInstanceOf(AssertionError.class);
        }

        @Test
        void assertOnlyFinishedSpan_returns_assert() {
            harness.tracer().spanBuilder("only").startSpan().end();
            SpanAssertions.assertOnlyFinishedSpan(harness.exporter()).hasName("only");
        }

        @Test
        void assertSpanByName_finds_correct_span() {
            harness.tracer().spanBuilder("find-me").startSpan().end();
            harness.tracer().spanBuilder("ignore").startSpan().end();
            SpanAssertions.assertSpanByName(harness.exporter(), "find-me")
                    .hasName("find-me");
        }

        @Test
        void assertSpanByName_fails_when_not_found() {
            harness.tracer().spanBuilder("other").startSpan().end();
            assertThatThrownBy(() ->
                    SpanAssertions.assertSpanByName(harness.exporter(), "missing"))
                    .isInstanceOf(AssertionError.class);
        }
    }
}
