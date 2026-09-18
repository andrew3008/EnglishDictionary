package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

class ScrubbingSpanProcessorAdvancedTest {

    @Test
    void empty_rule_list_does_not_crash_processor_and_does_not_change_attributes() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(
                ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(List.of()))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("user.email", "ivan@example.com");
            // when
            span.end();

            // then
            assertThat(h.exporter().getFinishedSpanItems().getFirst().getAttributes()
                    .get(AttributeKey.stringKey("user.email"))).isEqualTo("ivan@example.com");
        }
    }

    @Test
    void merge_strict_action_wins_over_soft() {
        // given
        AtomicInteger dropInvocations = new AtomicInteger();
        AtomicInteger maskInvocations = new AtomicInteger();

        SpanAttributeScrubbingRule dropRule = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override public String name() { return "drop-rule"; }
            @Override public int priority() { return 10; }
            @Nonnull
            @Override public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                dropInvocations.incrementAndGet();
                return ScrubbingDecision.drop("drop-rule");
            }
        };
        SpanAttributeScrubbingRule maskRule = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override public String name() { return "mask-rule"; }
            @Override public int priority() { return 100; }
            @Nonnull
            @Override public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                maskInvocations.incrementAndGet();
                return ScrubbingDecision.mask("mask-rule");
            }
        };

        try (SpanProcessorHarness h = SpanProcessorHarness.of(
                ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(List.of(maskRule, dropRule)))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("any", "x");
            // when
            span.end();

            // then
            assertThat(h.exporter().getFinishedSpanItems().getFirst().getAttributes().get(AttributeKey.stringKey("any")))
                    .isEmpty();
            assertThat(dropInvocations.get()).isEqualTo(1);
            assertThat(maskInvocations.get()).isEqualTo(1);
        }
    }

    @Test
    void non_string_DROP_overwrites_type_neutral_sentinel() {
        // given
        SpanAttributeScrubbingRule dropDoubles = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override public String name() { return "geo"; }
            @Override public int priority() { return 50; }
            @Nonnull
            @Override public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                return key.contains("lat")
                        ? ScrubbingDecision.drop("geo")
                        : ScrubbingDecision.keep();
            }
        };

        try (SpanProcessorHarness h = SpanProcessorHarness.of(
                ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(List.of(dropDoubles)))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("geo.lat", 55.751244d);
            span.setAttribute("geo.other", 1.5d);
            // when
            span.end();

            Attributes attributes = h.exporter().getFinishedSpanItems().getFirst().getAttributes();
            // then
            assertThat(attributes.get(AttributeKey.doubleKey("geo.lat"))).isEqualTo(0.0d);
            assertThat(attributes.get(AttributeKey.doubleKey("geo.other"))).isEqualTo(1.5d);
        }
    }

    @Test
    void exception_in_custom_rule_is_isolated_by_circuit_breaker_without_crashing_processor() {
        // given
        SpanAttributeScrubbingRule faulty = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override public String name() { return "boom"; }
            @Override public int priority() { return 900; }
            @Nonnull
            @Override public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                if ("bad".equals(key)) {
                    throw new RuntimeException("simulated rule failure");
                }
                return ScrubbingDecision.keep();
            }
        };

        PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(
                List.of(ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(List.of(faulty))));
        try (SpanProcessorHarness h = SpanProcessorHarness.of(composite)) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("ok", "fine");
            span.setAttribute("bad", "explodes");
            // when
            span.end();

            Attributes attributes = h.exporter().getFinishedSpanItems().getFirst().getAttributes();
            // then
            assertThat(attributes.get(AttributeKey.stringKey("ok"))).isEqualTo("fine");
            assertThat(attributes.get(AttributeKey.stringKey("bad"))).isEqualTo("explodes");
            assertThat(composite.getProcessorErrorCounts())
                    .containsEntry("scrubbing", 0L);
        }
    }
}
