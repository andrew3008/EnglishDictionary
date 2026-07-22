package space.br1440.platform.tracing.otel.javaagent.processor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeEngine;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleExecutionWrapper;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.test.assertions.ScrubbingAssertions;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * High-risk security characterization scrubbing (PR-5B).
 */
class ScrubbingSecurityCharacterizationTest {

    @Test
    void merge_precedence_drop_beats_hash_on_authorization_key() {
        var drop = new RuleExecutionWrapper(
                BuiltInSpanAttributeScrubbingRules.resolve("oauth-header"),
                new RuleCircuitBreaker("oauth-header"));
        var hash = new RuleExecutionWrapper(
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
    void malicious_regex_rule_failure_does_not_break_export() {
        SpanAttributeScrubbingRule redosLike = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override public String name() { return "redos-like"; }
            @Override public int priority() { return 900; }
            @Nonnull
            @Override public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                if ("bad".equals(key)) {
                    throw new StackOverflowError("simulated");
                }
                return ScrubbingDecision.keep();
            }
        };

        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ScrubbingSpanProcessor(List.of(redosLike)))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("ok", "fine");
            span.setAttribute("bad", "trigger");
            span.end();

            var attrs = h.exporter().getFinishedSpanItems().get(0).getAttributes();
            ScrubbingAssertions.assertExportAlive(h.exporter().getFinishedSpanItems().get(0));
            assertThat(attrs.get(AttributeKey.stringKey("ok"))).isEqualTo("fine");
            assertThat(attrs.get(AttributeKey.stringKey("bad"))).isEqualTo("trigger");
        }
    }
}
