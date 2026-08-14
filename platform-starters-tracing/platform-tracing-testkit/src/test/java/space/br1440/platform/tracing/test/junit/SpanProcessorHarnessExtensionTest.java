package space.br1440.platform.tracing.test.junit;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SpanProcessorHarnessExtension}:
 * lifecycle (SDK shared per class, exporter reset per test), parameter injection,
 * {@code @Nested}, factory null-guards, stateless factory.
 */
class SpanProcessorHarnessExtensionTest {

    /** Processor that stamps {@code tag=ext-test} at start. */
    static class TagProcessor implements SpanProcessor {
        @Override public void onStart(Context ctx, ReadWriteSpan span) { span.setAttribute("tag", "ext-test"); }
        @Override public boolean isStartRequired() { return true; }
        @Override public void onEnd(ReadableSpan span) {}
        @Override public boolean isEndRequired() { return false; }
    }

    @RegisterExtension
    static final SpanProcessorHarnessExtension ext =
            SpanProcessorHarnessExtension.of(TagProcessor::new);

    // -------------------------------------------------------------------------
    // Parameter injection
    // -------------------------------------------------------------------------

    @Test
    void parameter_injection_provides_non_null_harness(SpanProcessorHarness h) {
        assertThat(h).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Harness is usable via injected parameter
    // -------------------------------------------------------------------------

    @Test
    void harness_captures_processed_span(SpanProcessorHarness h) {
        h.tracer("test").spanBuilder("op").startSpan().end();

        assertThat(h.exporter().getFinishedSpanItems())
                .singleElement()
                .satisfies(s -> assertThat(
                        s.getAttributes().get(
                                io.opentelemetry.api.common.AttributeKey.stringKey("tag")))
                        .isEqualTo("ext-test"));
    }

    // -------------------------------------------------------------------------
    // Exporter is reset between tests (no span leak)
    // -------------------------------------------------------------------------

    @Test
    void exporter_is_empty_at_start_of_test(SpanProcessorHarness h) {
        assertThat(h.exporter().getFinishedSpanItems()).isEmpty();
    }

    @Test
    void spans_added_in_one_test_do_not_appear_in_next(SpanProcessorHarness h) {
        h.tracer("test").spanBuilder("leak-candidate").startSpan().end();
        assertThat(h.exporter().getFinishedSpanItems()).hasSize(1);
        // next test will start with empty exporter
    }

    @Test
    void second_test_sees_clean_exporter(SpanProcessorHarness h) {
        assertThat(h.exporter().getFinishedSpanItems()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // SpanKind propagation
    // -------------------------------------------------------------------------

    @Test
    void server_span_kind_is_captured(SpanProcessorHarness h) {
        h.tracer("test").spanBuilder("srv")
                .setSpanKind(SpanKind.SERVER)
                .startSpan().end();

        assertThat(h.exporter().getFinishedSpanItems())
                .singleElement()
                .satisfies(s -> assertThat(s.getKind()).isEqualTo(SpanKind.SERVER));
    }

    // -------------------------------------------------------------------------
    // @Nested injection
    // -------------------------------------------------------------------------

    @Nested
    class NestedTests {

        @Test
        void nested_injection_works(SpanProcessorHarness h) {
            assertThat(h).isNotNull();
        }

        @Test
        void nested_exporter_is_reset(SpanProcessorHarness h) {
            assertThat(h.exporter().getFinishedSpanItems()).isEmpty();
        }

        @Test
        void nested_can_capture_spans(SpanProcessorHarness h) {
            h.tracer("nested").spanBuilder("nested-op").startSpan().end();
            assertThat(h.exporter().getFinishedSpanItems()).hasSize(1);
        }
    }

    // -------------------------------------------------------------------------
    // Null-guard on factories
    // -------------------------------------------------------------------------

    @Test
    void null_supplier_factory_throws() {
        assertThatThrownBy(() -> SpanProcessorHarnessExtension.of((java.util.function.Supplier<SpanProcessor>) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void null_processor_factory_throws() {
        assertThatThrownBy(() -> SpanProcessorHarnessExtension.of((SpanProcessor) null))
                .isInstanceOf(NullPointerException.class);
    }

    // -------------------------------------------------------------------------
    // Stateless processor factory (of(SpanProcessor))
    // -------------------------------------------------------------------------

    @Test
    void stateless_factory_can_be_created() {
        SpanProcessorHarnessExtension e = SpanProcessorHarnessExtension.of(new TagProcessor());
        assertThat(e).isNotNull();
    }
}
