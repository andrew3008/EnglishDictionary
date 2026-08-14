package space.br1440.platform.tracing.otel.javaagent.processor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeEngine;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.test.assertions.ScrubbingAssertions;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * High-risk security characterization scrubbing (PR-5B).
 */
class ScrubbingSecurityCharacterizationTest {

    @Test
    void merge_precedence_drop_beats_hash_on_authorization_key() {
        var drop = new ExecutableRule(
                BuiltInSpanAttributeScrubbingRules.resolve("oauth-header"),
                new RuleCircuitBreaker("oauth-header"));
        var hash = new ExecutableRule(
                BuiltInSpanAttributeScrubbingRules.resolve("email"),
                new RuleCircuitBreaker("email"));

        var decision = MergeEngine.evaluate(
                List.of(hash, drop),
                "http.request.header.authorization",
                "user@example.com");

        assertThat(decision.action().name()).isEqualTo("DROP");
        assertThat(decision.reason()).isEqualTo("oauth-header");
    }

    @Test
    void prefixed_password_key_is_scrubbed() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ScrubbingSpanProcessor(
                ScrubbingCharacterizationSupport.resolveRules(List.of("password"))))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("app.client-secret", "abc");
            span.end();

            ScrubbingAssertions.assertStringAttributeEmpty(
                    h.exporter().getFinishedSpanItems().get(0), "app.client-secret");
        }
    }

    @Test
    void event_attributes_not_scrubbed_by_span_processor_observed_behavior() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ScrubbingSpanProcessor(
                ScrubbingCharacterizationSupport.resolveRules(List.of("oauth-header"))))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.addEvent("auth", Attributes.of(
                    AttributeKey.stringKey("http.request.header.authorization"), "Bearer secret"));
            span.end();

            String eventValue = h.exporter().getFinishedSpanItems().get(0).getEvents().get(0)
                    .getAttributes().get(AttributeKey.stringKey("http.request.header.authorization"));
            assertThat(eventValue).isEqualTo("Bearer secret");
        }
    }

    @Test
    void fatal_rule_failure_propagates_before_circuit_breaker() {
        StackOverflowError fatal = new StackOverflowError("simulated");
        SpanAttributeScrubbingRule redosLike = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override public String name() { return "redos-like"; }
            @Override public int priority() { return 900; }
            @Nonnull
            @Override public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                if ("bad".equals(key)) {
                    throw fatal;
                }
                return ScrubbingDecision.keep();
            }
        };

        ExecutableRule executable = new ExecutableRule(
                redosLike,
                new RuleCircuitBreaker("redos-like"));

        assertThatThrownBy(() -> executable.execute("bad", "trigger")).isSameAs(fatal);
    }
}
