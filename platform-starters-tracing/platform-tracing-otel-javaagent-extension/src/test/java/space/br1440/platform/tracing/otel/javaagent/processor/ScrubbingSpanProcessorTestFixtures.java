package space.br1440.platform.tracing.otel.javaagent.processor;

import java.util.List;

import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

import lombok.experimental.UtilityClass;

@UtilityClass
final class ScrubbingSpanProcessorTestFixtures {

    static ScrubbingSpanProcessor fromBuiltInRules(List<SpanAttributeScrubbingRule> rules) {
        return new ScrubbingSpanProcessor(ScrubbingRuntimeTestFixtures.fromBuiltInRules(rules));
    }

    static ScrubbingSpanProcessor fromBuiltInRules(List<SpanAttributeScrubbingRule> rules,
                                                   MaskedSecret hmacKey,
                                                   boolean failFast) {
        ScrubbingRuntime runtime = ScrubbingRuntimeTestFixtures.fromBuiltInRules(rules, hmacKey, failFast);
        return new ScrubbingSpanProcessor(runtime);
    }
}
