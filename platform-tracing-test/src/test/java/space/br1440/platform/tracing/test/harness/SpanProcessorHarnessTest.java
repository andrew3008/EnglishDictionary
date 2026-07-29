package space.br1440.platform.tracing.test.harness;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SpanProcessorHarness}.
 * Target: ≥95 % line coverage, ≥90 % branch coverage (plan §8.5).
 */
class SpanProcessorHarnessTest {

    private static final SpanProcessor NOOP = SpanProcessor.composite();

    @Test
    void spans_empty_initially() throws Exception {
        try (var h = SpanProcessorHarness.of(NOOP)) {
            assertThat(h.spans()).isEmpty();
        }
    }

    @Test
    void spans_captures_finished_span() throws Exception {
        try (var h = SpanProcessorHarness.of(NOOP)) {
            h.tracer().spanBuilder("op").startSpan().end();
            assertThat(h.spans()).hasSize(1);
            assertThat(h.spans().get(0).getName()).isEqualTo("op");
        }
    }

    @Test
    void spans_captures_multiple() throws Exception {
        try (var h = SpanProcessorHarness.of(NOOP)) {
            h.tracer().spanBuilder("a").startSpan().end();
            h.tracer().spanBuilder("b").startSpan().end();
            h.tracer().spanBuilder("c").startSpan().end();
            assertThat(h.spans()).hasSize(3);
        }
    }

    @Test
    void reset_clears_spans() throws Exception {
        try (var h = SpanProcessorHarness.of(NOOP)) {
            h.tracer().spanBuilder("op").startSpan().end();
            assertThat(h.spans()).hasSize(1);
            h.reset();
            assertThat(h.spans()).isEmpty();
        }
    }

    @Test
    void tracer_no_arg_scope_is_test() throws Exception {
        try (var h = SpanProcessorHarness.of(NOOP)) {
            h.tracer().spanBuilder("scoped").startSpan().end();
            List<SpanData> spans = h.spans();
            assertThat(spans.get(0).getInstrumentationScopeInfo().getName())
                    .isEqualTo("test");
        }
    }

    @Test
    void tracer_named_uses_custom_scope() throws Exception {
        try (var h = SpanProcessorHarness.of(NOOP)) {
            h.tracer("my-lib").spanBuilder("op").startSpan().end();
            assertThat(h.spans().get(0).getInstrumentationScopeInfo().getName())
                    .isEqualTo("my-lib");
        }
    }

    @Test
    void exporter_returns_same_data_as_spans() throws Exception {
        try (var h = SpanProcessorHarness.of(NOOP)) {
            h.tracer().spanBuilder("op").startSpan().end();
            assertThat(h.exporter().getFinishedSpanItems())
                    .isSameAs(h.spans());
        }
    }

    @Test
    void openTelemetry_not_null() throws Exception {
        try (var h = SpanProcessorHarness.of(NOOP)) {
            assertThat(h.openTelemetry()).isNotNull();
        }
    }

    @Test
    void of_with_non_null_resource() throws Exception {
        try (var h = SpanProcessorHarness.of(NOOP, Resource.getDefault())) {
            h.tracer().spanBuilder("op").startSpan().end();
            assertThat(h.spans()).hasSize(1);
        }
    }

    @Test
    void of_with_null_resource_uses_sdk_default() throws Exception {
        try (var h = SpanProcessorHarness.of(NOOP, null)) {
            h.tracer().spanBuilder("op").startSpan().end();
            assertThat(h.spans()).hasSize(1);
        }
    }

    @Test
    void null_processor_throws() {
        assertThatThrownBy(() -> SpanProcessorHarness.of(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void null_scope_name_throws() throws Exception {
        try (var h = SpanProcessorHarness.of(NOOP)) {
            assertThatThrownBy(() -> h.tracer(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void span_kind_preserved() throws Exception {
        try (var h = SpanProcessorHarness.of(NOOP)) {
            h.tracer().spanBuilder("srv")
                    .setSpanKind(SpanKind.SERVER)
                    .startSpan().end();
            assertThat(h.spans().get(0).getKind()).isEqualTo(SpanKind.SERVER);
        }
    }

    @Test
    void close_is_idempotent() throws Exception {
        var h = SpanProcessorHarness.of(NOOP);
        h.close();
        h.close(); // must not throw
    }
}
