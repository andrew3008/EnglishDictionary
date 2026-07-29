package space.br1440.platform.tracing.test.junit;

import io.opentelemetry.sdk.trace.SpanProcessor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SpanProcessorHarnessExtension}:
 * lifecycle (fresh harness per test, close after each), parameter injection, {@code @Nested}.
 */
class SpanProcessorHarnessExtensionTest {

    @RegisterExtension
    static final SpanProcessorHarnessExtension ext =
            SpanProcessorHarnessExtension.of(SpanProcessor::composite);

    @Test
    void parameter_injection_provides_non_null_harness(SpanProcessorHarness h) {
        assertThat(h).isNotNull();
    }

    @Test
    void spans_empty_at_start_of_each_test(SpanProcessorHarness h) {
        // fresh harness created in beforeEach — exporter is always empty
        assertThat(h.spans()).isEmpty();
    }

    @Test
    void can_record_and_read_spans(SpanProcessorHarness h) {
        h.tracer().spanBuilder("op").startSpan().end();
        assertThat(h.spans()).hasSize(1);
        assertThat(h.spans().get(0).getName()).isEqualTo("op");
    }

    @Test
    void second_test_also_starts_empty(SpanProcessorHarness h) {
        // regardless of execution order, each test gets a fresh harness
        assertThat(h.spans()).isEmpty();
        h.tracer().spanBuilder("op2").startSpan().end();
        assertThat(h.spans()).hasSize(1);
    }

    @Nested
    class NestedTests {

        @Test
        void nested_injection_works(SpanProcessorHarness h) {
            assertThat(h).isNotNull();
            h.tracer().spanBuilder("nested-op").startSpan().end();
            assertThat(h.spans()).hasSize(1);
        }

        @Test
        void nested_spans_empty_at_start(SpanProcessorHarness h) {
            assertThat(h.spans()).isEmpty();
        }
    }

    @Test
    void null_factory_throws() {
        assertThatThrownBy(() -> SpanProcessorHarnessExtension.of(null))
                .isInstanceOf(NullPointerException.class);
    }
}
