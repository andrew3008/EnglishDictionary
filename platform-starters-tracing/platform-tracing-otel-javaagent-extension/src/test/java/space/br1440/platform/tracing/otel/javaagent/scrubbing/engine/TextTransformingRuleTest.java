package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;

class TextTransformingRuleTest {

    @Test
    void subsequentRulesObserveOnlyTransformedText() {
        // given
        AtomicReference<Object> observed = new AtomicReference<>();
        SpanAttributeScrubbingRule observer = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return "observer";
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                observed.set(value);
                return ScrubbingDecision.truncate("observer", 4);
            }
        };

        // when
        MergeOutcome outcome = MergeEngine.evaluateDetailed(
                List.of(executable(new MaskingRule(false)), executable(observer)),
                "exception.message", "secret");

        // then
        assertThat(observed.get()).isEqualTo("safe text");
        assertThat(outcome.maskedValue()).isEqualTo("safe text");
        assertThat(outcome.decision().action()).isEqualTo(ScrubbingAction.TRUNCATE);
    }

    @Test
    void transformFailureAndOpenBreakerRemainFailClosed() {
        // given
        MaskingRule rule = new MaskingRule(true);
        RuleCircuitBreaker breaker = new RuleCircuitBreaker(rule.name(), 1, 60_000L, 60_000L);
        ExecutableRule executable = new ExecutableRule(rule, breaker);

        // when
        MergeOutcome failed = MergeEngine.evaluateDetailed(List.of(executable), "exception.message", "secret");
        MergeOutcome blocked = MergeEngine.evaluateDetailed(List.of(executable), "exception.message", "secret");

        // then
        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.getTotalFailures()).isEqualTo(1L);
        for (MergeOutcome outcome : List.of(failed, blocked)) {
            assertThat(outcome.criticalFailures()).isEqualTo(1L);
            assertThat(outcome.decision().reason()).isEqualTo(ScrubbingDecision.SCRUBBING_FAILED_REASON);
            assertThat(outcome.decision().action()).isEqualTo(ScrubbingAction.MASK);
            assertThat(outcome.maskedValue()).isNull();
        }
    }

    private static ExecutableRule executable(SpanAttributeScrubbingRule rule) {
        return new ExecutableRule(rule, new RuleCircuitBreaker(rule.name()));
    }

    private record MaskingRule(boolean failing) implements TextTransformingRule {
        @Nonnull
        @Override
        public String name() {
            return "text-mask";
        }

        @Override
        public boolean critical() {
            return true;
        }

        @Override
        public String transform(@Nonnull String key, @Nonnull String value) {
            if (failing) {
                throw new IllegalArgumentException("Simulated parser failure");
            }
            return "safe text";
        }
    }
}
