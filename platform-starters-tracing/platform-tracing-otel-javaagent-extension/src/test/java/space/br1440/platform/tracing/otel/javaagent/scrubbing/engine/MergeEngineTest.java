package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;

class MergeEngineTest {

    private static SpanAttributeScrubbingRule rule(boolean critical, int priority, ScrubbingDecision result) {
        return new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override public String name() { return "r" + priority + critical; }
            @Override public int priority() { return priority; }
            @Override public boolean critical() { return critical; }
            @Nonnull
            @Override public ScrubbingDecision evaluate(@Nonnull String key, Object value) { return result; }
        };
    }

    private static ExecutableRule wrap(SpanAttributeScrubbingRule r) {
        return new ExecutableRule(r, new RuleCircuitBreaker(r.name()));
    }

    @Test
    void keep_does_not_weaken_mask() {
        // given
        ExecutableRule mask = wrap(rule(true, 10, ScrubbingDecision.mask("oauth")));
        ExecutableRule keep = wrap(rule(false, 900, ScrubbingDecision.keep()));

        // when
        ScrubbingDecision result = MergeEngine.evaluateDetailed(List.of(mask, keep), "authorization", "Bearer x").decision();
        // then
        assertThat(result.action()).isEqualTo(ScrubbingAction.MASK);
    }

    @Test
    void critical_drop_is_not_weakened_by_subsequent_hash() {
        // given
        ExecutableRule drop = wrap(rule(true, 10, ScrubbingDecision.drop("oauth")));
        ExecutableRule hash = wrap(rule(false, 130, ScrubbingDecision.hash("email")));

        // when
        ScrubbingDecision result = MergeEngine.evaluateDetailed(List.of(drop, hash), "x-authorization", "token").decision();
        // then
        assertThat(result.action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(result.terminal()).isTrue();
    }

    @Test
    void terminal_custom_rule_decision_does_not_block_subsequent_rule() {
        // given
        ExecutableRule customTerminalMask = wrap(rule(false, 900, ScrubbingDecision.maskTerminal("custom")));
        ExecutableRule laterDrop = wrap(rule(false, 950, ScrubbingDecision.drop("later")));

        // when
        ScrubbingDecision result = MergeEngine.evaluateDetailed(List.of(customTerminalMask, laterDrop), "some.key", "v").decision();
        // then
        assertThat(result.action()).isEqualTo(ScrubbingAction.DROP);
    }

    @Test
    void tie_breaker_drop_beats_mask_for_two_critical() {
        // given
        ExecutableRule criticalDrop = wrap(rule(true, 10, ScrubbingDecision.drop("oauth")));
        ExecutableRule criticalMask = wrap(rule(true, 20, ScrubbingDecision.mask("other")));

        // when
        ScrubbingDecision result = MergeEngine.evaluateDetailed(List.of(criticalDrop, criticalMask), "authorization", "s").decision();
        // then
        assertThat(result.action()).isEqualTo(ScrubbingAction.DROP);
    }

    @Test
    void all_keep_yields_keep() {
        // given
        ExecutableRule a = wrap(rule(false, 900, ScrubbingDecision.keep()));
        ExecutableRule b = wrap(rule(true, 10, ScrubbingDecision.keep()));
        // when/then
        assertThat(MergeEngine.evaluateDetailed(List.of(a, b), "k", "v").decision().action())
                .isEqualTo(ScrubbingAction.KEEP);
    }
}
