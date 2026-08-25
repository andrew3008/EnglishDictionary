package space.br1440.platform.tracing.test.harness;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import org.junit.jupiter.api.Test;

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SpanProcessorHarness}.
 * Target: ≥95 % line coverage, ≥90 % branch coverage (plan §8.5).
 */
class SpanProcessorHarnessTest {

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    /** Processor that stamps {@code marker=ok} on every span at start. */
    static class MarkerProcessor implements SpanProcessor {
        @Override public void onStart(Context ctx, ReadWriteSpan span) { span.setAttribute("marker", "ok"); }
        @Override public boolean isStartRequired() { return true; }
        @Override public void onEnd(ReadableSpan span) {}
        @Override public boolean isEndRequired() { return false; }
    }

    /** No-op processor for API surface tests. */
    static class NoopProcessor implements SpanProcessor {
        @Override public void onStart(Context ctx, ReadWriteSpan span) {}
        @Override public boolean isStartRequired() { return false; }
        @Override public void onEnd(ReadableSpan span) {}
        @Override public boolean isEndRequired() { return false; }
    }

    // -------------------------------------------------------------------------
    // of(processor) — basic construction and span capture
    // -------------------------------------------------------------------------

    @Test
    void harness_wraps_processor_and_captures_span() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new MarkerProcessor())) {
            h.tracer("test").spanBuilder("op").startSpan().end();

            assertThat(h.exporter().getFinishedSpanItems())
                    .singleElement()
                    .satisfies(s -> assertThat(s.getAttributes().get(stringKey("marker")))
                            .isEqualTo("ok"));
        }
    }

    @Test
    void multiple_spans_are_captured() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new NoopProcessor())) {
            h.tracer("test").spanBuilder("a").startSpan().end();
            h.tracer("test").spanBuilder("b").startSpan().end();
            h.tracer("test").spanBuilder("c").startSpan().end();

            assertThat(h.exporter().getFinishedSpanItems()).hasSize(3);
        }
    }

    @Test
    void exporter_reset_clears_spans() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new NoopProcessor())) {
            h.tracer("test").spanBuilder("first").startSpan().end();
            assertThat(h.exporter().getFinishedSpanItems()).hasSize(1);

            h.exporter().reset();
            assertThat(h.exporter().getFinishedSpanItems()).isEmpty();

            h.tracer("test").spanBuilder("second").startSpan().end();
            assertThat(h.exporter().getFinishedSpanItems()).hasSize(1);
        }
    }

    @Test
    void tracer_no_arg_and_spans_helpers() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new NoopProcessor())) {
            h.tracer().spanBuilder("via-default").startSpan().end();
            assertThat(h.spans()).hasSize(1);

            h.reset();
            assertThat(h.spans()).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // of(processor, resource)
    // -------------------------------------------------------------------------

    @Test
    void of_with_resource_applies_service_name() {
        Resource resource = Resource.builder()
                .put("service.name", "my-service")
                .build();
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new NoopProcessor(), resource)) {
            h.tracer("test").spanBuilder("op").startSpan().end();

            assertThat(h.exporter().getFinishedSpanItems())
                    .singleElement()
                    .satisfies(s -> assertThat(
                            s.getResource().getAttribute(stringKey("service.name")))
                            .isEqualTo("my-service"));
        }
    }

    @Test
    void of_with_null_resource_uses_default() {
        assertThatCode(() -> {
            try (SpanProcessorHarness h = SpanProcessorHarness.of(new NoopProcessor(), null)) {
                h.tracer("test").spanBuilder("op").startSpan().end();
                assertThat(h.exporter().getFinishedSpanItems()).hasSize(1);
            }
        }).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // API accessors
    // -------------------------------------------------------------------------

    @Test
    void tracer_returns_functional_tracer() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new NoopProcessor())) {
            Span span = h.tracer("my-scope").spanBuilder("op").startSpan();
            span.end();
            assertThat(h.exporter().getFinishedSpanItems())
                    .singleElement()
                    .satisfies(s -> assertThat(s.getInstrumentationScopeInfo().getName())
                            .isEqualTo("my-scope"));
        }
    }

    @Test
    void openTelemetry_is_not_null() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new NoopProcessor())) {
            assertThat(h.openTelemetry()).isNotNull();
        }
    }

    @Test
    void exporter_is_not_null() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new NoopProcessor())) {
            assertThat(h.exporter()).isNotNull();
        }
    }

    // -------------------------------------------------------------------------
    // SpanKind propagation
    // -------------------------------------------------------------------------

    @Test
    void span_kind_is_preserved_in_captured_span() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new NoopProcessor())) {
            h.tracer("test").spanBuilder("srv")
                    .setSpanKind(SpanKind.SERVER)
                    .startSpan().end();

            assertThat(h.exporter().getFinishedSpanItems())
                    .singleElement()
                    .satisfies(s -> assertThat(s.getKind()).isEqualTo(SpanKind.SERVER));
        }
    }

    // -------------------------------------------------------------------------
    // close()
    // -------------------------------------------------------------------------

    @Test
    void close_does_not_throw() {
        SpanProcessorHarness h = SpanProcessorHarness.of(new NoopProcessor());
        assertThatCode(h::close).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Null-guard
    // -------------------------------------------------------------------------

    @Test
    void null_processor_throws() {
        assertThatThrownBy(() -> SpanProcessorHarness.of(null))
                .isInstanceOf(NullPointerException.class);
    }
}
