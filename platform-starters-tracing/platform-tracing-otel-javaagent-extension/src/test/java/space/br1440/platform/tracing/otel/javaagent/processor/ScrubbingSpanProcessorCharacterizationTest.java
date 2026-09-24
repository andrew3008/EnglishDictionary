package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

/**
 * Characterization {@link ScrubbingSpanProcessor} integration behavior.
 */
class ScrubbingSpanProcessorCharacterizationTest {

    @Test
    void mandatory_scrubbing_enabled_by_default() {
        // given
        ScrubbingRuntime runtime = ScrubbingRuntimeTestFixtures.fromBuiltInRules(
                ScrubbingCharacterizationSupport.resolveRules(List.of("password"))
        );

        try (ScrubbingSpanProcessor ignored = new ScrubbingSpanProcessor(runtime)) {
            // then
            assertThat(runtime.enabled()).isTrue();
        }
    }

    @Test
    void custom_rule_hash_is_applied_on_processor_path() {
        // given
        SpanAttributeScrubbingRule customHash = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return "custom-hash";
            }

            @Override
            public int priority() {
                return 200;
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                return key.contains("secret")
                        ? ScrubbingDecision.hash("custom-hash")
                        : ScrubbingDecision.keep();
            }
        };

        try (SpanProcessorHarness h = SpanProcessorHarness.of(ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(
                List.of(customHash), MaskedSecret.of("hmac-key"), false))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("my.secret.token", "value");
            // when
            span.end();

            String out = h.exporter().getFinishedSpanItems().getFirst().getAttributes()
                    .get(io.opentelemetry.api.common.AttributeKey.stringKey("my.secret.token"));
            // then
            assertThat(out).matches("[0-9a-f]{64}");
        }
    }

    @Test
    void built_in_email_with_hmac_produces_deterministic_hash() {
        // given
        String first;
        String second;
        try (SpanProcessorHarness h = SpanProcessorHarness.of(ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(
                List.of(BuiltInSpanAttributeScrubbingRules.EMAIL.create()),
                MaskedSecret.of("secret-key"),
                false))
        ) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("user.email", "ivan@example.com");
            span.end();
            first = h.exporter().getFinishedSpanItems().getFirst().getAttributes()
                    .get(io.opentelemetry.api.common.AttributeKey.stringKey("user.email"));
        }

        try (SpanProcessorHarness h = SpanProcessorHarness.of(ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(
                List.of(BuiltInSpanAttributeScrubbingRules.EMAIL.create()),
                MaskedSecret.of("secret-key"), false))
        ) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("user.email", "ivan@example.com");
            // when
            span.end();
            second = h.exporter().getFinishedSpanItems().getFirst().getAttributes()
                    .get(io.opentelemetry.api.common.AttributeKey.stringKey("user.email"));
        }
        // then
        assertThat(first).isEqualTo(second).matches("[0-9a-f]{64}");
    }
}
