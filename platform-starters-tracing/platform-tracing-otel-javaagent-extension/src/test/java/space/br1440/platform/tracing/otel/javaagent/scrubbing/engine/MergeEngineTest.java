package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты accumulate-and-merge движка (PR-3): «KEEP never weakens», игнор терминальных решений
 * custom-правил, тай-брейкер двух critical, ранний выход.
 */
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
        var mask = wrap(rule(true, 10, ScrubbingDecision.mask("oauth")));
        var keep = wrap(rule(false, 900, ScrubbingDecision.keep()));

        ScrubbingDecision result = MergeEngine.evaluate(List.of(mask, keep), "authorization", "Bearer x");
        assertThat(result.action()).isEqualTo(ScrubbingAction.MASK);
    }

    @Test
    void critical_drop_is_not_weakened_by_subsequent_hash() {
        var drop = wrap(rule(true, 10, ScrubbingDecision.drop("oauth")));
        var hash = wrap(rule(false, 130, ScrubbingDecision.hash("email")));

        ScrubbingDecision result = MergeEngine.evaluate(List.of(drop, hash), "x-authorization", "token");
        assertThat(result.action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(result.terminal()).isTrue();
    }

    @Test
    void terminal_custom_rule_decision_does_not_block_subsequent_rule() {
        // Custom-правило вернуло maskTerminal — если бы terminal учитывался, был бы early-exit и
        // MASK заблокировал бы последующее. Но для custom terminal игнорируется → DROP перекрывает.
        var customTerminalMask = wrap(rule(false, 900, ScrubbingDecision.maskTerminal("custom")));
        var laterDrop = wrap(rule(false, 950, ScrubbingDecision.drop("later")));

        ScrubbingDecision result = MergeEngine.evaluate(List.of(customTerminalMask, laterDrop), "some.key", "v");
        assertThat(result.action()).isEqualTo(ScrubbingAction.DROP);
    }

    @Test
    void tie_breaker_drop_beats_mask_for_two_critical() {
        var criticalDrop = wrap(rule(true, 10, ScrubbingDecision.drop("oauth")));
        var criticalMask = wrap(rule(true, 20, ScrubbingDecision.mask("other")));

        ScrubbingDecision result = MergeEngine.evaluate(List.of(criticalDrop, criticalMask), "authorization", "s");
        assertThat(result.action()).isEqualTo(ScrubbingAction.DROP);
    }

    @Test
    void all_keep_yields_keep() {
        var a = wrap(rule(false, 900, ScrubbingDecision.keep()));
        var b = wrap(rule(true, 10, ScrubbingDecision.keep()));
        assertThat(MergeEngine.evaluate(List.of(a, b), "k", "v").action()).isEqualTo(ScrubbingAction.KEEP);
    }
}
