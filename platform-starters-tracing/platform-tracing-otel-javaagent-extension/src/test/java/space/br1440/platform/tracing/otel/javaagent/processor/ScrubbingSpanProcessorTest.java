package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

class ScrubbingSpanProcessorTest {

    @Test
    void drop_by_password_key_name_overwrites_with_empty_string() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(List.of(
                BuiltInSpanAttributeScrubbingRules.PASSWORD_KEY.create()
        )))) {
            Tracer tracer = h.tracer("test");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("db.password", "supersecret");
            span.setAttribute("just.text", "ничего интересного");
            // when
            span.end();

            // then
            Attributes attributes = h.exporter().getFinishedSpanItems().getFirst().getAttributes();
            assertThat(attributes.get(AttributeKey.stringKey("db.password"))).isEmpty();
            assertThat(attributes.get(AttributeKey.stringKey("just.text"))).isEqualTo("ничего интересного");
        }
    }

    @Test
    void jwt_value_is_fully_removed() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(List.of(
                BuiltInSpanAttributeScrubbingRules.JWT.create()
        )))) {
            Tracer tracer = h.tracer("test");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("token.value", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.signature");
            // when
            span.end();

            Attributes attributes = h.exporter().getFinishedSpanItems().getFirst().getAttributes();
            // then
            assertThat(attributes.get(AttributeKey.stringKey("token.value"))).isEmpty();
        }
    }

    @Test
    void email_is_hashed_when_hmac_key_is_set() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(List.of(
                BuiltInSpanAttributeScrubbingRules.EMAIL.create()
        ), MaskedSecret.of("secret-key"), false))) {
            Tracer tracer = h.tracer("test");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("user.email", "ivan.petrov@example.com");
            // when
            span.end();

            String value = h.exporter().getFinishedSpanItems().getFirst().getAttributes()
                    .get(AttributeKey.stringKey("user.email"));
            // then
            assertThat(value)
                    .doesNotContain("ivan.petrov@example.com")
                    .hasSize(64) // HMAC-SHA256 hex
                    .matches("[0-9a-f]{64}");
        }
    }

    @Test
    void email_degrades_to_mask_without_hmac_key() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(List.of(
                BuiltInSpanAttributeScrubbingRules.EMAIL.create()
        )))) {
            Tracer tracer = h.tracer("test");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("user.email", "ivan.petrov@example.com");
            // when
            span.end();

            // then
            assertThat(h.exporter().getFinishedSpanItems().getFirst().getAttributes()
                    .get(AttributeKey.stringKey("user.email")))
                    .isEqualTo("***")
                    .doesNotContain("ivan");
        }
    }

    @Test
    void oauth_authorization_header_is_removed() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(List.of(
                BuiltInSpanAttributeScrubbingRules.OAUTH_HEADER.create()
        )))) {
            Tracer tracer = h.tracer("test");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("http.request.header.authorization", "Bearer super-secret-token");
            // when
            span.end();

            // then
            assertThat(h.exporter().getFinishedSpanItems().getFirst().getAttributes()
                    .get(AttributeKey.stringKey("http.request.header.authorization")))
                    .isEmpty();
        }
    }
}
