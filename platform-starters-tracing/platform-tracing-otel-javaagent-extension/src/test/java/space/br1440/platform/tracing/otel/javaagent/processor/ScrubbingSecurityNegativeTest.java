package space.br1440.platform.tracing.otel.javaagent.processor;


import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScrubbingSecurityNegativeTest {

    @Test
    void rule_priority_conflict_authorization_is_dropped_not_hashed() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(List.of(
                BuiltInSpanAttributeScrubbingRules.EMAIL.create(),
                BuiltInSpanAttributeScrubbingRules.OAUTH_HEADER.create()
        ), MaskedSecret.of("key"), false))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("http.request.header.authorization", "user@example.com");
            // when
            span.end();

            // then
            assertThat(h.exporter().getFinishedSpanItems().getFirst().getAttributes()
                    .get(AttributeKey.stringKey("http.request.header.authorization")))
                    .isEmpty();
        }
    }

    @Test
    void drop_integration_secret_absent_from_exported_SpanData() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(List.of(
                BuiltInSpanAttributeScrubbingRules.OAUTH_HEADER.create()
        )))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("http.request.header.authorization", "Bearer super-secret-token");
            // when
            span.end();

            Attributes attributes = h.exporter().getFinishedSpanItems().getFirst().getAttributes();
            attributes.forEach((k, v) ->
                    // then
                    assertThat(String.valueOf(v)).doesNotContain("super-secret-token"));
        }
    }

    @Test
    void mixed_ip_header_masks_entire_value_in_exported_span() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(List.of(
                BuiltInSpanAttributeScrubbingRules.IP_ADDRESS.create()
        )))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("http.request.header.x-forwarded-for", "1.2.3.4, attacker.example");
            span.setAttribute("client.ip", "2001:db8::1");
            // when
            span.end();

            Attributes attributes = h.exporter().getFinishedSpanItems().getFirst().getAttributes();
            // then
            assertThat(attributes.get(AttributeKey.stringKey("http.request.header.x-forwarded-for")))
                    .isEqualTo("***")
                    .doesNotContain("attacker.example");
            assertThat(attributes.get(AttributeKey.stringKey("client.ip")))
                    .isEqualTo("2001:db8:0:0::");
        }
    }

    @Test
    void hmac_determinism_same_key_yields_same_hash() {
        // given
        String h1 = hashEmail("secret-key");
        String h2 = hashEmail("secret-key");
        String h3 = hashEmail("another-key");

        // then
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).isNotEqualTo(h3);
    }

    @Test
    void fail_fast_without_key_throws_at_startup() {
        // given
        // then
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> {
            try (ScrubbingSpanProcessor ignored = ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(
                    List.of(BuiltInSpanAttributeScrubbingRules.EMAIL.create()), null, true)) {
                org.junit.jupiter.api.Assertions.fail("fail-fast startup must throw before the processor is returned");
            }
        });
    }

    @Test
    void event_scope_token_in_event_is_not_scrubbed_by_SDK_processor() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(List.of(
                BuiltInSpanAttributeScrubbingRules.OAUTH_HEADER.create()
        )))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.addEvent("auth", Attributes.of(
                    AttributeKey.stringKey("http.request.header.authorization"), "Bearer secret"));
            // when
            span.end();

            String eventAttr = h.exporter().getFinishedSpanItems().getFirst().getEvents().getFirst()
                    .getAttributes().get(AttributeKey.stringKey("http.request.header.authorization"));
            // then
            assertThat(eventAttr).isEqualTo("Bearer secret");
        }
    }

    private static String hashEmail(String key) {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(List.of(
                BuiltInSpanAttributeScrubbingRules.EMAIL.create()
        ), MaskedSecret.of(key), false))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("user.email", "ivan@example.com");
            span.end();
            return h.exporter().getFinishedSpanItems().getFirst().getAttributes()
                    .get(AttributeKey.stringKey("user.email"));
        }
    }
}
