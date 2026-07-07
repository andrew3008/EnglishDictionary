package space.br1440.platform.tracing.otel.extension.processor;


import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.extension.scrubbing.BuiltInSensitiveDataRules;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScrubbingSpanProcessorTest {

    @Test
    void drop_по_имени_password_ключа_перезаписывает_пустой_строкой() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ScrubbingSpanProcessor(List.of(
                BuiltInSensitiveDataRules.resolve("password")
        )))) {
            Tracer tracer = h.tracer("test");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("db.password", "supersecret");
            span.setAttribute("just.text", "ничего интересного");
            span.end();

            var attributes = h.exporter().getFinishedSpanItems().get(0).getAttributes();
            assertThat(attributes.get(AttributeKey.stringKey("db.password"))).isEmpty();
            assertThat(attributes.get(AttributeKey.stringKey("just.text"))).isEqualTo("ничего интересного");
        }
    }

    @Test
    void jwt_значение_полностью_удаляется() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ScrubbingSpanProcessor(List.of(
                BuiltInSensitiveDataRules.resolve("jwt")
        )))) {
            Tracer tracer = h.tracer("test");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("token.value", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.signature");
            span.end();

            var attributes = h.exporter().getFinishedSpanItems().get(0).getAttributes();
            assertThat(attributes.get(AttributeKey.stringKey("token.value"))).isEmpty();
        }
    }

    @Test
    void email_хэшируется_когда_задан_hmac_ключ() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ScrubbingSpanProcessor(List.of(
                BuiltInSensitiveDataRules.resolve("email")
        ), "secret-key", false))) {
            Tracer tracer = h.tracer("test");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("user.email", "ivan.petrov@example.com");
            span.end();

            String value = h.exporter().getFinishedSpanItems().get(0).getAttributes()
                    .get(AttributeKey.stringKey("user.email"));
            assertThat(value)
                    .doesNotContain("ivan.petrov@example.com")
                    .hasSize(64) // HMAC-SHA256 hex
                    .matches("[0-9a-f]{64}");
        }
    }

    @Test
    void email_деградирует_до_маски_без_hmac_ключа() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ScrubbingSpanProcessor(List.of(
                BuiltInSensitiveDataRules.resolve("email")
        )))) {
            Tracer tracer = h.tracer("test");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("user.email", "ivan.petrov@example.com");
            span.end();

            assertThat(h.exporter().getFinishedSpanItems().get(0).getAttributes()
                    .get(AttributeKey.stringKey("user.email")))
                    .isEqualTo("***")
                    .doesNotContain("ivan");
        }
    }

    @Test
    void oauth_authorization_заголовок_удаляется() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ScrubbingSpanProcessor(List.of(
                BuiltInSensitiveDataRules.resolve("oauth-header")
        )))) {
            Tracer tracer = h.tracer("test");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("http.request.header.authorization", "Bearer super-secret-token");
            span.end();

            assertThat(h.exporter().getFinishedSpanItems().get(0).getAttributes()
                    .get(AttributeKey.stringKey("http.request.header.authorization")))
                    .isEmpty();
        }
    }
}
