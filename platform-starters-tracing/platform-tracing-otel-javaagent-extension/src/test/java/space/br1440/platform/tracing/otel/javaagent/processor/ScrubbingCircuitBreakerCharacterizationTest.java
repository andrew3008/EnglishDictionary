package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static space.br1440.platform.tracing.api.spi.ScrubbingDecision.SCRUBBING_FAILED_REASON;

import java.util.List;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeEngine;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeOutcome;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

class ScrubbingCircuitBreakerCharacterizationTest {

    @Test
    void critical_rule_open_returns_scrubbing_failed_in_merge_engine() {
        // given
        SpanAttributeScrubbingRule criticalFaulty = criticalThrowingRule("critical-open");
        ExecutableRule wrapper = new ExecutableRule(
                criticalFaulty,
                new RuleCircuitBreaker("critical-open", 1, 60_000L, 0L)
        );

        MergeEngine.evaluateDetailed(List.of(wrapper), "secret.key", "value");
        ScrubbingDecision openDecision = MergeEngine.evaluateDetailed(
                        List.of(wrapper),
                        "secret.key",
                        "value"
                )
                .decision();

        // then
        assertThat(openDecision.action().name()).isEqualTo("MASK");
        assertThat(openDecision.reason()).isEqualTo(SCRUBBING_FAILED_REASON);
    }

    @Test
    void critical_rule_failure_masks_attribute_with_scrubbing_failed() {
        // given
        SpanAttributeScrubbingRule criticalFaulty = criticalThrowingRule("critical-boom");

        try (SpanProcessorHarness h = SpanProcessorHarness.of(
                ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(List.of(criticalFaulty))
        )) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("secret.key", "leak-me");
            // when
            span.end();

            // then
            assertThat(h.exporter().getFinishedSpanItems().getFirst().getAttributes()
                    .get(AttributeKey.stringKey("secret.key")))
                    .isEqualTo(SCRUBBING_FAILED_REASON);
        }
    }

    @Test
    void custom_rule_open_is_skipped_by_merge_engine() {
        // given
        SpanAttributeScrubbingRule customFaulty = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return "custom-open";
            }

            @Override
            public int priority() {
                return 900;
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                throw new RuntimeException("boom");
            }
        };

        ExecutableRule wrapper = new ExecutableRule(
                customFaulty,
                new RuleCircuitBreaker("custom-open", 1, 60_000L, 0L)
        );
        MergeEngine.evaluateDetailed(List.of(wrapper), "k", "v");
        MergeOutcome openResult = MergeEngine.evaluateDetailed(List.of(wrapper), "k", "v");

        // then
        assertThat(openResult.skippedCircuitOpen()).isEqualTo(1L);
        assertThat(openResult.decision().action().name()).isEqualTo("KEEP");
    }

    private static SpanAttributeScrubbingRule criticalThrowingRule(String name) {
        return new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return name;
            }

            @Override
            public int priority() {
                return 10;
            }

            @Override
            public boolean critical() {
                return true;
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                throw new RuntimeException("simulated critical failure");
            }
        };
    }
}
