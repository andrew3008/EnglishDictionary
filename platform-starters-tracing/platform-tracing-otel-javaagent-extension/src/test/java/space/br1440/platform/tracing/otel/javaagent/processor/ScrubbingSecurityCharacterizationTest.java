package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeEngine;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.test.assertions.ScrubbingAssertions;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

class ScrubbingSecurityCharacterizationTest {

    @Test
    void merge_precedence_drop_beats_hash_on_authorization_key() {
        // given
        ExecutableRule drop = new ExecutableRule(
                BuiltInSpanAttributeScrubbingRules.OAUTH_HEADER.create(),
                new RuleCircuitBreaker("oauth-header"));
        ExecutableRule hash = new ExecutableRule(
                BuiltInSpanAttributeScrubbingRules.EMAIL.create(),
                new RuleCircuitBreaker("email"));

        ScrubbingDecision decision = MergeEngine.evaluateDetailed(
                List.of(hash, drop),
                "http.request.header.authorization",
                "user@example.com").decision();

        // then
        assertThat(decision.action().name()).isEqualTo("DROP");
        assertThat(decision.reason()).isEqualTo("oauth-header");
    }

    @Test
    void prefixed_password_key_is_scrubbed() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(
                ScrubbingCharacterizationSupport.resolveRules(List.of("password"))))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("app.client-secret", "abc");
            // when
            span.end();

            ScrubbingAssertions.assertStringAttributeEmpty(
                    h.exporter().getFinishedSpanItems().getFirst(),
                    "app.client-secret"
            );
        }
    }

    @Test
    void event_attributes_not_scrubbed_by_span_processor_observed_behavior() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(
                ScrubbingCharacterizationSupport.resolveRules(List.of("oauth-header"))))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.addEvent("auth", Attributes.of(
                    AttributeKey.stringKey("http.request.header.authorization"), "Bearer secret"));
            // when
            span.end();

            String eventValue = h.exporter().getFinishedSpanItems().getFirst().getEvents().getFirst()
                    .getAttributes().get(AttributeKey.stringKey("http.request.header.authorization"));
            // then
            assertThat(eventValue).isEqualTo("Bearer secret");
        }
    }

    @Test
    void fatal_rule_failure_propagates_before_circuit_breaker() {
        // given
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

        // then
        assertThatThrownBy(() -> MergeEngine.evaluateDetailed(List.of(executable), "bad", "trigger"))
                .isSameAs(fatal);
    }
}
