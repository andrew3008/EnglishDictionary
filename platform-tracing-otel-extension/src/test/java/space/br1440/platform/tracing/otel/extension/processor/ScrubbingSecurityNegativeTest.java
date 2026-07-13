package space.br1440.platform.tracing.otel.extension.processor;


import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.extension.scrubbing.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-negative проверки: гарантии того, что секреты не утекают в экспортированную SpanData,
 * детерминизм HMAC, разрешение конфликтов правил и честная граница scope (events не чистятся).
 */
class ScrubbingSecurityNegativeTest {

    @Test
    void rule_priority_conflict_authorization_дропается_а_не_хэшируется() {
        // authorization матчит и OAuthHeaderRule (DROP, priority 10), и EmailRule по значению
        // (HASH, priority 130). Должен победить DROP независимо от порядка регистрации.
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ScrubbingSpanProcessor(List.of(
                BuiltInSpanAttributeScrubbingRules.resolve("email"),
                BuiltInSpanAttributeScrubbingRules.resolve("oauth-header")
        ), "key", false))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("http.request.header.authorization", "user@example.com");
            span.end();

            assertThat(h.exporter().getFinishedSpanItems().get(0).getAttributes()
                    .get(AttributeKey.stringKey("http.request.header.authorization")))
                    .isEmpty();
        }
    }

    @Test
    void drop_integration_секрет_отсутствует_в_экспортированной_SpanData() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ScrubbingSpanProcessor(List.of(
                BuiltInSpanAttributeScrubbingRules.resolve("oauth-header")
        )))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("http.request.header.authorization", "Bearer super-secret-token");
            span.end();

            Attributes attributes = h.exporter().getFinishedSpanItems().get(0).getAttributes();
            attributes.forEach((k, v) ->
                    assertThat(String.valueOf(v)).doesNotContain("super-secret-token"));
        }
    }

    @Test
    void hmac_детерминизм_одинаковый_ключ_даёт_одинаковый_хэш() {
        String h1 = hashEmail("secret-key");
        String h2 = hashEmail("secret-key");
        String h3 = hashEmail("another-key");

        assertThat(h1).isEqualTo(h2);
        assertThat(h1).isNotEqualTo(h3);
    }

    @Test
    void fail_fast_без_ключа_бросает_на_старте() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new ScrubbingSpanProcessor(List.of(BuiltInSpanAttributeScrubbingRules.resolve("email")), null, true));
    }

    @Test
    void event_scope_токен_в_событии_не_вычищается_SDK_процессором() {
        // Документируем честную границу: ScrubbingSpanProcessor мутирует только span attributes.
        // Атрибуты событий через ReadWriteSpan не модифицируются — это safety net Collector'а.
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ScrubbingSpanProcessor(List.of(
                BuiltInSpanAttributeScrubbingRules.resolve("oauth-header")
        )))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.addEvent("auth", Attributes.of(
                    AttributeKey.stringKey("http.request.header.authorization"), "Bearer secret"));
            span.end();

            String eventAttr = h.exporter().getFinishedSpanItems().get(0).getEvents().get(0)
                    .getAttributes().get(AttributeKey.stringKey("http.request.header.authorization"));
            assertThat(eventAttr).isEqualTo("Bearer secret"); // НЕ вычищено — ожидаемое поведение
        }
    }

    private static String hashEmail(String key) {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ScrubbingSpanProcessor(List.of(
                BuiltInSpanAttributeScrubbingRules.resolve("email")
        ), key, false))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("user.email", "ivan@example.com");
            span.end();
            return h.exporter().getFinishedSpanItems().get(0).getAttributes()
                    .get(AttributeKey.stringKey("user.email"));
        }
    }
}
