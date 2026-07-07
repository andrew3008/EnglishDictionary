package space.br1440.platform.tracing.otel.extension.processor;


import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SensitiveDataRule;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Дополнительные кейсы {@link ScrubbingSpanProcessor}: пустой набор правил, приоритет правил
 * (выигрывает первое сматчившееся в порядке возрастания priority), нестроковый DROP через
 * sentinel, изоляция исключений правила.
 */
class ScrubbingSpanProcessorAdvancedTest {

    @Test
    void пустой_список_правил_не_роняет_процессор_и_не_изменяет_атрибуты() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ScrubbingSpanProcessor(List.of()))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("user.email", "ivan@example.com");
            span.end();

            assertThat(h.exporter().getFinishedSpanItems().get(0).getAttributes()
                    .get(AttributeKey.stringKey("user.email"))).isEqualTo("ivan@example.com");
        }
    }

    @Test
    void merge_строгое_действие_выигрывает_над_мягким() {
        // Merge-движок (PR-3) обходит ВСЕ правила и сводит решения по строгости:
        // оба custom-правила вызываются, побеждает более строгое DROP над MASK.
        AtomicInteger dropInvocations = new AtomicInteger();
        AtomicInteger maskInvocations = new AtomicInteger();

        SensitiveDataRule dropRule = new SensitiveDataRule() {
            @Override public String name() { return "drop-rule"; }
            @Override public int priority() { return 10; }
            @Override public ScrubbingDecision evaluate(String key, Object value) {
                dropInvocations.incrementAndGet();
                return ScrubbingDecision.drop("drop-rule");
            }
        };
        SensitiveDataRule maskRule = new SensitiveDataRule() {
            @Override public String name() { return "mask-rule"; }
            @Override public int priority() { return 100; }
            @Override public ScrubbingDecision evaluate(String key, Object value) {
                maskInvocations.incrementAndGet();
                return ScrubbingDecision.mask("mask-rule");
            }
        };

        try (SpanProcessorHarness h = SpanProcessorHarness.of(
                new ScrubbingSpanProcessor(List.of(maskRule, dropRule)))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("any", "x");
            span.end();

            // DROP строже MASK → итог пустая строка. Оба custom-правила вызваны (нет early-return).
            assertThat(h.exporter().getFinishedSpanItems().get(0).getAttributes().get(AttributeKey.stringKey("any")))
                    .isEmpty();
            assertThat(dropInvocations.get()).isEqualTo(1);
            assertThat(maskInvocations.get()).isEqualTo(1);
        }
    }

    @Test
    void нестроковый_DROP_перезаписывает_type_neutral_sentinel() {
        SensitiveDataRule dropDoubles = new SensitiveDataRule() {
            @Override public String name() { return "geo"; }
            @Override public int priority() { return 50; }
            @Override public ScrubbingDecision evaluate(String key, Object value) {
                return key.contains("lat")
                        ? ScrubbingDecision.drop("geo")
                        : ScrubbingDecision.keep();
            }
        };

        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ScrubbingSpanProcessor(List.of(dropDoubles)))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("geo.lat", 55.751244d);
            span.setAttribute("geo.other", 1.5d);
            span.end();

            var attributes = h.exporter().getFinishedSpanItems().get(0).getAttributes();
            assertThat(attributes.get(AttributeKey.doubleKey("geo.lat"))).isEqualTo(0.0d);
            assertThat(attributes.get(AttributeKey.doubleKey("geo.other"))).isEqualTo(1.5d);
        }
    }

    @Test
    void exception_в_custom_правиле_изолируется_circuit_breaker_ом_не_роняя_процессор() {
        // PR-4/PR-5: сбой custom-правила теперь перехватывается в RuleExecutionWrapper и считается
        // в circuit breaker — он НЕ всплывает в PlatformCompositeSpanProcessor. Для custom-правила
        // (critical=false) поведение при сбое — skip: атрибут остаётся нетронутым, остальные целы.
        SensitiveDataRule faulty = new SensitiveDataRule() {
            @Override public String name() { return "boom"; }
            @Override public int priority() { return 900; }
            @Override public ScrubbingDecision evaluate(String key, Object value) {
                if ("bad".equals(key)) {
                    throw new RuntimeException("simulated rule failure");
                }
                return ScrubbingDecision.keep();
            }
        };

        PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(
                List.of(new ScrubbingSpanProcessor(List.of(faulty))));
        try (SpanProcessorHarness h = SpanProcessorHarness.of(composite)) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("ok", "fine");
            span.setAttribute("bad", "explodes");
            span.end();

            var attributes = h.exporter().getFinishedSpanItems().get(0).getAttributes();
            // Span экспортирован, оба атрибута на месте: сбой правила изолирован, без краха процессора.
            assertThat(attributes.get(AttributeKey.stringKey("ok"))).isEqualTo("fine");
            assertThat(attributes.get(AttributeKey.stringKey("bad"))).isEqualTo("explodes");
            // Ошибка НЕ всплыла в composite (счётчик 0) — изоляция теперь внутри circuit breaker.
            assertThat(composite.getProcessorErrorCounts())
                    .containsEntry("ScrubbingSpanProcessor", 0L);
        }
    }
}
