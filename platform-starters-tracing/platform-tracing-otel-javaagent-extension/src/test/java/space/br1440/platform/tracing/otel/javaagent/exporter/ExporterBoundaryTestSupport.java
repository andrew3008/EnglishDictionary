package space.br1440.platform.tracing.otel.javaagent.exporter;

import java.util.ArrayList;
import java.util.List;

import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOrigin;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuntimeRule;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.testing.trace.TestSpanData;

import lombok.experimental.UtilityClass;

@UtilityClass
final class ExporterBoundaryTestSupport {

    static final SpanContext LINK_TARGET = SpanContext.create(
            "00000000000000000000000000000abc", "000000000000abc1",
            TraceFlags.getDefault(), TraceState.getDefault());

    static ScrubbingRuntime runtimeOf(List<SpanAttributeScrubbingRule> builtIn) {
        return runtimeOf(builtIn, List.of(), null);
    }

    static ScrubbingRuntime runtimeOf(List<SpanAttributeScrubbingRule> builtIn, String hmacKey) {
        return runtimeOf(builtIn, List.of(), hmacKey);
    }

    static ScrubbingRuntime runtimeOf(List<SpanAttributeScrubbingRule> builtIn,
                                      List<SpanAttributeScrubbingRule> externalExtension,
                                      String hmacKey) {
        List<RuntimeRule> rules = new ArrayList<>();

        for (SpanAttributeScrubbingRule rule : builtIn) {
            rules.add(new RuntimeRule(new ExecutableRule(rule, new RuleCircuitBreaker(rule.name())), RuleOrigin.BUILT_IN));
        }

        for (SpanAttributeScrubbingRule rule : externalExtension) {
            rules.add(new RuntimeRule(new ExecutableRule(rule, new RuleCircuitBreaker(rule.name())), RuleOrigin.EXTERNAL_EXTENSION));
        }

        return ScrubbingRuntimeTestFixtures.create(
                rules, 1L, MaskedSecret.of(hmacKey), false
        );
    }

    static TestSpanData.Builder baseSpan(String name) {
        return TestSpanData.builder()
                .setName(name)
                .setKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                .setStatus(StatusData.unset())
                .setStartEpochNanos(0L)
                .setEndEpochNanos(1L)
                .setHasEnded(true);
    }
}
