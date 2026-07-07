package space.br1440.platform.tracing.test.harness;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpanProcessorHarnessTest {

    /** Простой процессор-тестируемый: ставит атрибут {@code marker=ok} при старте span'а. */
    static class MarkerProcessor implements SpanProcessor {
        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
            span.setAttribute("marker", "ok");
        }

        @Override
        public boolean isStartRequired() {
            return true;
        }

        @Override
        public void onEnd(ReadableSpan span) {
        }

        @Override
        public boolean isEndRequired() {
            return false;
        }
    }

    @Test
    void harness_оборачивает_процессор_и_собирает_span() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new MarkerProcessor())) {
            Span span = h.tracer("test").spanBuilder("op").startSpan();
            span.end();

            assertThat(h.exporter().getFinishedSpanItems())
                    .singleElement()
                    .satisfies(s -> assertThat(s.getAttributes().get(AttributeKey.stringKey("marker"))).isEqualTo("ok"));
        }
    }
}
