package space.br1440.platform.tracing.otel.javaagent.exporter.boundary;

import java.util.ArrayList;
import java.util.List;

import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOrigin;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuntimeRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.sdk.trace.SpanLimits;
import lombok.experimental.UtilityClass;

@UtilityClass
public class BoundaryTestSupport {

    public static ScrubbingRuntime runtimeOf(List<SpanAttributeScrubbingRule> builtIn) {
        return runtimeOf(builtIn, List.of(), null);
    }

    public static ScrubbingRuntime runtimeOf(List<SpanAttributeScrubbingRule> builtIn, String hmacKey) {
        return runtimeOf(builtIn, List.of(), hmacKey);
    }

    public static ScrubbingRuntime runtimeOf(List<SpanAttributeScrubbingRule> builtIn,
                                              List<SpanAttributeScrubbingRule> externalExtension,
                                              String hmacKey) {
        return runtimeOf(builtIn, externalExtension, hmacKey, SpanLimits.getDefault().getMaxAttributeValueLength());
    }

    public static ScrubbingRuntime runtimeOf(List<SpanAttributeScrubbingRule> builtIn,
                                             List<SpanAttributeScrubbingRule> externalExtension,
                                             String hmacKey,
                                             int attributeLimit) {
        List<RuntimeRule> rules = new ArrayList<>();

        for (SpanAttributeScrubbingRule rule : builtIn) {
            rules.add(new RuntimeRule(new ExecutableRule(rule, new RuleCircuitBreaker(rule.name())), RuleOrigin.BUILT_IN));
        }

        for (SpanAttributeScrubbingRule rule : externalExtension) {
            rules.add(new RuntimeRule(new ExecutableRule(rule, new RuleCircuitBreaker(rule.name())), RuleOrigin.EXTERNAL_EXTENSION));
        }

        ScrubbingRuntime runtime = ScrubbingRuntimeTestFixtures.create(
                rules, 1L, MaskedSecret.of(hmacKey), false
        );
        runtime.bindAttributeValueLengthLimit(attributeLimit);
        return runtime;
    }
}
