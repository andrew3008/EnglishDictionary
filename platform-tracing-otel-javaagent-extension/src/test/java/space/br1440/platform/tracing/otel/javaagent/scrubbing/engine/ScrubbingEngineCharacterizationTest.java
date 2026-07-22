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
 * Characterization {@link MergeEngine} merge semantics (PR-5B).
 */
class ScrubbingEngineCharacterizationTest {

    private static RuleExecutionWrapper wrap(SpanAttributeScrubbingRule rule) {
        return new RuleExecutionWrapper(rule, new RuleCircuitBreaker(rule.name()));
    }

    private static SpanAttributeScrubbingRule rule(boolean critical, int priority, ScrubbingDecision result) {
        return new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override public String name() { return "r" + priority; }
            @Override public int priority() { return priority; }
            @Override public boolean critical() { return critical; }
            @Nonnull
            @Override public ScrubbingDecision evaluate(@Nonnull String key, Object value) { return result; }
        };
    }

    @Test
    void keep_never_weakens_drop() {
        var drop = wrap(rule(true, 10, ScrubbingDecision.drop("oauth")));
        var keep = wrap(rule(false, 900, ScrubbingDecision.keep()));
        assertThat(MergeEngine.evaluate(List.of(drop, keep), "k", "v").action())
                .isEqualTo(ScrubbingAction.DROP);
    }

    @Test
    void all_keep_yields_keep() {
        var a = wrap(rule(false, 900, ScrubbingDecision.keep()));
        var b = wrap(rule(true, 10, ScrubbingDecision.keep()));
        assertThat(MergeEngine.evaluate(List.of(a, b), "k", "v").action())
                .isEqualTo(ScrubbingAction.KEEP);
    }
}
