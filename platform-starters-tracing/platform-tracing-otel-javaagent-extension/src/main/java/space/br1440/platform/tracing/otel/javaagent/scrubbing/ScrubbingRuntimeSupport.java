package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import java.util.ArrayList;
import java.util.List;

import lombok.experimental.UtilityClass;

import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.PriorityHardening;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOrigin;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuntimeRule;

@UtilityClass
public final class ScrubbingRuntimeSupport {

    public static ScrubbingRuntime fromBuiltInRules(List<SpanAttributeScrubbingRule> rules,
                                                    MaskedSecret hmacKey,
                                                    boolean failFast) {
        List<RuntimeRule> tagged = new ArrayList<>(rules.size());
        for (SpanAttributeScrubbingRule rule : rules) {
            tagged.add(new RuntimeRule(
                    new ExecutableRule(rule, new RuleCircuitBreaker(rule.name())),
                    RuleOrigin.BUILT_IN
            ));
        }

        List<RuntimeRule> sorted = PriorityHardening.sortAndClampRuntimeRules(tagged);
        return ScrubbingRuntime.create(sorted, 1L, "startup", hmacKey, failFast);
    }
}
