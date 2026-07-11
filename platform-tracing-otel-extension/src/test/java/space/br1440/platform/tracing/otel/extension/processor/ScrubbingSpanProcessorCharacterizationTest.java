package space.br1440.platform.tracing.otel.extension.processor;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SensitiveDataRule;
import space.br1440.platform.tracing.otel.extension.scrubbing.BuiltInSensitiveDataRules;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization {@link ScrubbingSpanProcessor} integration behavior (PR-5B).
 */
class ScrubbingSpanProcessorCharacterizationTest {

    @Test
    void mandatory_scrubbing_enabled_by_default() {
        ScrubbingSpanProcessor processor = new ScrubbingSpanProcessor(
                ScrubbingCharacterizationSupport.resolveRules(List.of("password")));
        assertThat(processor.isEnabled()).isTrue();
    }

    @Test
    void custom_rule_hash_is_applied_on_processor_path() {
        SensitiveDataRule customHash = new SensitiveDataRule() {
            @Nonnull
            @Override public String name() { return "custom-hash"; }
            @Override public int priority() { return 200; }
            @Nonnull
            @Override public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                return key.contains("secret")
                        ? ScrubbingDecision.hash("custom-hash")
                        : ScrubbingDecision.keep();
            }
        };

        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ScrubbingSpanProcessor(
                List.of(customHash), "hmac-key", false))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("my.secret.token", "value");
            span.end();

            String out = h.exporter().getFinishedSpanItems().get(0).getAttributes()
                    .get(io.opentelemetry.api.common.AttributeKey.stringKey("my.secret.token"));
            assertThat(out).matches("[0-9a-f]{64}");
        }
    }

    @Test
    void built_in_email_with_hmac_produces_deterministic_hash() {
        String first;
        String second;
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ScrubbingSpanProcessor(
                List.of(BuiltInSensitiveDataRules.resolve("email")), "secret-key", false))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("user.email", "ivan@example.com");
            span.end();
            first = h.exporter().getFinishedSpanItems().get(0).getAttributes()
                    .get(io.opentelemetry.api.common.AttributeKey.stringKey("user.email"));
        }
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ScrubbingSpanProcessor(
                List.of(BuiltInSensitiveDataRules.resolve("email")), "secret-key", false))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("user.email", "ivan@example.com");
            span.end();
            second = h.exporter().getFinishedSpanItems().get(0).getAttributes()
                    .get(io.opentelemetry.api.common.AttributeKey.stringKey("user.email"));
        }
        assertThat(first).isEqualTo(second).matches("[0-9a-f]{64}");
    }
}
