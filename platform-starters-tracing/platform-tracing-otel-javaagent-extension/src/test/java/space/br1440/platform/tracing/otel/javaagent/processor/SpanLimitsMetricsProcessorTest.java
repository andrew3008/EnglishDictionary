package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.util.List;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.data.SpanData;

class SpanLimitsMetricsProcessorTest {

    @Test
    void overwritingAnAttributeAtTheLimitDoesNotCountAsADrop() {
        // given
        SpanLimitsMetricsProcessor processor = new SpanLimitsMetricsProcessor();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .setSpanLimits(SpanLimits.builder().setMaxNumberOfAttributes(1).build())
                .addSpanProcessor(processor)
                .build()) {
            ReadWriteSpan span = (ReadWriteSpan) provider.get("span-limits-test").spanBuilder("overwrite").startSpan();

            // when
            span.setAttribute("key", "first");
            span.setAttribute("key", "second");
            span.setAttribute("key", "third");
            span.end();

            // then
            SpanData data = span.toSpanData();
            assertThat(data.getAttributes().get(AttributeKey.stringKey("key"))).isEqualTo("third");
            assertThat(data.getAttributes().size()).isEqualTo(1);
            assertThat(data.getTotalAttributeCount()).isEqualTo(1);
            assertThat(processor.getDroppedAttributes()).isZero();
        }
    }

    @Test
    void attributesBeyondTheSdkLimitIncrementTheDropCounter() {
        // given
        SpanLimitsMetricsProcessor processor = new SpanLimitsMetricsProcessor();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .setSpanLimits(SpanLimits.builder().setMaxNumberOfAttributes(1).build())
                .addSpanProcessor(processor)
                .build()) {
            ReadWriteSpan span = (ReadWriteSpan) provider.get("span-limits-test").spanBuilder("overflow").startSpan();

            // when
            span.setAttribute("first", "retained");
            span.setAttribute("second", "dropped");
            span.setAttribute("third", "dropped");
            span.end();

            // then
            SpanData data = span.toSpanData();
            assertThat(data.getAttributes().asMap()).containsOnlyKeys(AttributeKey.stringKey("first"));
            assertThat(data.getTotalAttributeCount()).isEqualTo(3);
            assertThat(processor.getDroppedAttributes()).isEqualTo(2);
        }
    }

    @Test
    void inPlaceScrubbingBeforeOnEndDoesNotCountAsAnSdkDrop() {
        // given
        SpanLimitsMetricsProcessor processor = new SpanLimitsMetricsProcessor();
        ScrubbingRuntime runtime = ScrubbingRuntimeTestFixtures.fromBuiltInRules(
                List.of(BuiltInSpanAttributeScrubbingRules.PASSWORD_KEY.create()));
        PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(
                List.of(processor, new ScrubbingSpanProcessor(runtime)));
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .setSpanLimits(SpanLimits.builder().setMaxNumberOfAttributes(1).build())
                .addSpanProcessor(composite)
                .build()) {
            ReadWriteSpan span = (ReadWriteSpan) provider.get("span-limits-test").spanBuilder("scrubbing").startSpan();
            span.setAttribute("db.password", "supersecret");

            // when
            span.end();

            // then
            SpanData data = span.toSpanData();
            assertThat(data.getAttributes().get(AttributeKey.stringKey("db.password"))).isEmpty();
            assertThat(data.getAttributes().size()).isEqualTo(1);
            assertThat(data.getTotalAttributeCount()).isEqualTo(1);
            assertThat(runtime.droppedActions()).isEqualTo(1);
            assertThat(processor.getDroppedAttributes()).isZero();
            assertThat(composite.getProcessorErrorCounts().values()).allMatch(count -> count == 0L);
        }
    }

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
