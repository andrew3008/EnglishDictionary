package space.br1440.platform.tracing.otel.javaagent.processor;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization classification processor behavior (PR-5B).
 */
class ClassificationCharacterizationTest {

    @Test
    void error_span_gets_high_priority_observed_behavior() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ClassificationSpanProcessor(
                Duration.ofSeconds(10), Duration.ofSeconds(1)))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setStatus(StatusCode.ERROR, "boom");
            span.end();

            var attrs = h.exporter().getFinishedSpanItems().get(0).getAttributes();
            assertThat(attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey(
                    PlatformAttributes.PLATFORM_TRACE_PRIORITY))).isEqualTo("high");
        }
    }
}
