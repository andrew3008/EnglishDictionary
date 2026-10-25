package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;

class ScrubbingEngineCharacterizationTest {

    private static ExecutableRule wrap(SpanAttributeScrubbingRule rule) {
        return new ExecutableRule(rule, new RuleCircuitBreaker(rule.name()));
    }

    private static SpanAttributeScrubbingRule rule(boolean critical, int priority, ScrubbingDecision result) {
        return new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return "r" + priority;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override public boolean critical() {
                return critical;
            }

            @Nonnull
            @Override public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                return result;
            }
        };
    }

    @Test
    void keep_never_weakens_drop() {
        // given
        ExecutableRule drop = wrap(rule(true, 10, ScrubbingDecision.drop("oauth")));
        ExecutableRule keep = wrap(rule(false, 900, ScrubbingDecision.keep()));
        // when/then
        assertThat(MergeEngine.evaluateDetailed(List.of(drop, keep), "k", "v").decision().action())
                .isEqualTo(ScrubbingAction.DROP);
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

    @Test
    void priority_floor_applies_only_to_non_critical_external_origins() {
        // given
        RuntimeRule builtIn = new RuntimeRule(
                wrap(rule(false, 130, ScrubbingDecision.keep())),
                RuleOrigin.BUILT_IN
        );

        RuntimeRule bundled = new RuntimeRule(
                wrap(rule(false, 140, ScrubbingDecision.keep())),
                RuleOrigin.BUNDLED_SPI
        );

        RuntimeRule external = new RuntimeRule(
                wrap(rule(false, 150, ScrubbingDecision.keep())),
                RuleOrigin.EXTERNAL_EXTENSION
        );

        // when
        List<RuntimeRule> hardened = PriorityHardening.sortAndClampRuntimeRules(
                List.of(builtIn, bundled, external)
        );

        // then
        assertThat(priorityOf(hardened, RuleOrigin.BUILT_IN)).isEqualTo(130);
        assertThat(priorityOf(hardened, RuleOrigin.BUNDLED_SPI))
                .isEqualTo(PriorityHardening.CUSTOM_PRIORITY_FLOOR);
        assertThat(priorityOf(hardened, RuleOrigin.EXTERNAL_EXTENSION))
                .isEqualTo(PriorityHardening.CUSTOM_PRIORITY_FLOOR);
    }

    private static int priorityOf(List<RuntimeRule> rules, RuleOrigin origin) {
        return rules.stream()
                .filter(rule -> rule.origin() == origin)
                .map(RuntimeRule::executable)
                .mapToInt(ExecutableRule::getEffectivePriority)
                .findFirst()
                .orElseThrow();
    }
}
