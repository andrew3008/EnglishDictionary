package space.br1440.platform.tracing.otel.javaagent.processor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeEngine;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleExecutionWrapper;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static space.br1440.platform.tracing.api.spi.ScrubbingDecision.SCRUBBING_FAILED_REASON;

/**
 * Characterization circuit breaker + fail-closed scrubbing (PR-5B).
 */
class ScrubbingCircuitBreakerCharacterizationTest {

    @Test
    void critical_rule_open_returns_scrubbing_failed_in_merge_engine() {
        SpanAttributeScrubbingRule criticalFaulty = criticalThrowingRule("critical-open");
        var wrapper = new RuleExecutionWrapper(
                criticalFaulty,
                new RuleCircuitBreaker("critical-open", 1, 0L, 0L));

        wrapper.execute("secret.key", "value");
        var openDecision = wrapper.execute("secret.key", "value");

        assertThat(openDecision.action().name()).isEqualTo("MASK");
        assertThat(openDecision.reason()).isEqualTo(SCRUBBING_FAILED_REASON);
    }

    @Test
    void critical_rule_failure_masks_attribute_with_scrubbing_failed() {
        SpanAttributeScrubbingRule criticalFaulty = criticalThrowingRule("critical-boom");

        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ScrubbingSpanProcessor(List.of(criticalFaulty)))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("secret.key", "leak-me");
            span.end();

            assertThat(h.exporter().getFinishedSpanItems().get(0).getAttributes()
                    .get(AttributeKey.stringKey("secret.key")))
                    .isEqualTo(SCRUBBING_FAILED_REASON);
        }
    }

    @Test
    void custom_rule_open_is_skipped_by_merge_engine() {
        SpanAttributeScrubbingRule customFaulty = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override public String name() { return "custom-open"; }
            @Override public int priority() { return 900; }
            @Nonnull
            @Override public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                throw new RuntimeException("boom");
            }
        };
        var wrapper = new RuleExecutionWrapper(
                customFaulty,
                new RuleCircuitBreaker("custom-open", 1, 0L, 0L));
        wrapper.execute("k", "v");
        var openResult = wrapper.execute("k", "v");

        assertThat(openResult).isNull();
        assertThat(MergeEngine.evaluate(List.of(wrapper), "k", "v").action().name()).isEqualTo("KEEP");
    }

    private static SpanAttributeScrubbingRule criticalThrowingRule(String name) {
        return new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override public String name() { return name; }
            @Override public int priority() { return 10; }
            @Override public boolean critical() { return true; }
            @Nonnull
            @Override public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                throw new RuntimeException("simulated critical failure");
            }
        };
    }
}
