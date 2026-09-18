package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

class ClassificationCharacterizationTest {

    @Test
    void error_span_gets_high_priority_observed_behavior() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ClassificationSpanProcessor(
                Duration.ofSeconds(10), Duration.ofSeconds(1)))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setStatus(StatusCode.ERROR, "boom");
            // when
            span.end();

            // then
            Attributes attrs = h.exporter().getFinishedSpanItems().getFirst().getAttributes();
            assertThat(attrs.get(
                    AttributeKey.stringKey(PlatformAttributes.PLATFORM_TRACE_PRIORITY))
            ).isEqualTo("high");
        }
    }
}
