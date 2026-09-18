package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

class SpanLimitsMetricsProcessorTest {

    @Test
    void exposesOnlySpanLimitDropCountersAndStableDiagnosticIdentity() {
        try (SpanLimitsMetricsProcessor processor = new SpanLimitsMetricsProcessor()) {
            // then
            assertThat(processor.diagnosticId()).isEqualTo("span_limits_metrics");
            assertThat(Modifier.isFinal(SpanLimitsMetricsProcessor.class.getModifiers())).isTrue();
            assertThat(SpanLimitsMetricsProcessor.class.getDeclaredMethods())
                    .extracting(java.lang.reflect.Method::getName)
                    .doesNotContain("getEndedSpans", "getErrorSpans", "getTimeoutSpans")
                    .contains("getDroppedAttributes", "getDroppedEvents", "getDroppedLinks");
        }
    }
}
