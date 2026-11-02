package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;

class ScrubbingEngineCharacterizationTest {

    private static ExecutableRule wrap(SpanAttributeScrubbingRule rule) {
        return new ExecutableRule(rule, new RuleCircuitBreaker(rule.name()));
    }

    private static SpanAttributeScrubbingRule rule(int priority) {
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

            @Nonnull
            @Override public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                return ScrubbingDecision.keep();
            }
        };
    }

    @Test
    void priority_floor_applies_only_to_non_critical_external_origins() {
        // given
        RuntimeRule builtIn = new RuntimeRule(
                wrap(rule(130)),
                RuleOrigin.BUILT_IN
        );

        RuntimeRule bundled = new RuntimeRule(
                wrap(rule(140)),
                RuleOrigin.BUNDLED_SPI
        );

        RuntimeRule external = new RuntimeRule(
                wrap(rule(150)),
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
