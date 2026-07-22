package space.br1440.platform.tracing.otel.extension.processor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Дополнительные кейсы {@link EnrichingSpanProcessor}: кастомный приоритет атрибутов через
 * конструктор, фильтрация null/blank-имён, мэппинг {@link SpanKind} в категории.
 * <p>
 * Здесь каждый тест требует своей конфигурации процессора, поэтому используется
 * {@link SpanProcessorHarness} в {@code try-with-resources}, а не общий
 * {@code @RegisterExtension static OtelSdkExtension}.
 */
class EnrichingSpanProcessorAdvancedTest {

    private static final AttributeKey<String> PLATFORM_TYPE_KEY =
            AttributeKey.stringKey(PlatformAttributes.PLATFORM_TYPE);
    private static final AttributeKey<String> PLATFORM_RESULT_KEY =
            AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT);
    private static final AttributeKey<String> PLATFORM_REMOTE_SERVICE_KEY =
            AttributeKey.stringKey(PlatformAttributes.PLATFORM_REMOTE_SERVICE);

    @Test
    void кастомный_приоритет_атрибутов_передаётся_через_конструктор() {
        // Меняем порядок: сначала server.address, потом peer.service. Должны получить server.address.
        try (SpanProcessorHarness h = SpanProcessorHarness.of(
                new EnrichingSpanProcessor(List.of("server.address", "peer.service")))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("client").setSpanKind(SpanKind.CLIENT).startSpan();
            span.setAttribute("peer.service", "billing");
            span.setAttribute("server.address", "billing.example.com");
            span.setStatus(StatusCode.ERROR, "boom");
            span.end();

            assertThat(h.exporter().getFinishedSpanItems().get(0).getAttributes().get(PLATFORM_REMOTE_SERVICE_KEY))
                    .isEqualTo("billing.example.com");
        }
    }

    @Test
    void blank_и_null_имена_атрибутов_фильтруются_при_конструировании() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(
                new EnrichingSpanProcessor(Arrays.asList(null, "", "  ", "peer.service")))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("client").setSpanKind(SpanKind.CLIENT).startSpan();
            span.setAttribute("peer.service", "orders");
            span.setStatus(StatusCode.ERROR, "boom");
            span.end();

            assertThat(h.exporter().getFinishedSpanItems().get(0).getAttributes().get(PLATFORM_REMOTE_SERVICE_KEY))
                    .isEqualTo("orders");
        }
    }

    @Test
    void имена_атрибутов_триммятся_перед_использованием() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(
                new EnrichingSpanProcessor(List.of("  peer.service  ")))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("client").setSpanKind(SpanKind.CLIENT).startSpan();
            span.setAttribute("peer.service", "trim-test");
            span.setStatus(StatusCode.ERROR, "boom");
            span.end();

            assertThat(h.exporter().getFinishedSpanItems().get(0).getAttributes().get(PLATFORM_REMOTE_SERVICE_KEY))
                    .isEqualTo("trim-test");
        }
    }

    @Test
    void INTERNAL_kind_получает_категорию_internal() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new EnrichingSpanProcessor())) {
            h.tracer("t").spanBuilder("internal-op").setSpanKind(SpanKind.INTERNAL).startSpan().end();
            assertThat(h.exporter().getFinishedSpanItems().get(0).getAttributes().get(PLATFORM_TYPE_KEY))
                    .isEqualTo("internal");
        }
    }

    @Test
    void PRODUCER_и_CONSUMER_kind_получают_категории_kafka() {
        // Фаза 13: единая категория rpc разделена — PRODUCER→kafka_producer, CONSUMER→kafka_consumer.
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new EnrichingSpanProcessor())) {
            Tracer tracer = h.tracer("t");
            tracer.spanBuilder("p").setSpanKind(SpanKind.PRODUCER).startSpan().end();
            tracer.spanBuilder("c").setSpanKind(SpanKind.CONSUMER).startSpan().end();

            assertThat(h.exporter().getFinishedSpanItems().get(0).getAttributes().get(PLATFORM_TYPE_KEY)).isEqualTo("kafka_producer");
            assertThat(h.exporter().getFinishedSpanItems().get(1).getAttributes().get(PLATFORM_TYPE_KEY)).isEqualTo("kafka_consumer");
        }
    }

    @Test
    void platform_remote_service_не_перезаписывает_заранее_заданное_значение() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new EnrichingSpanProcessor())) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("client").setSpanKind(SpanKind.CLIENT).startSpan();
            span.setAttribute("peer.service", "auto-extracted");
            span.setAttribute(PlatformAttributes.PLATFORM_REMOTE_SERVICE, "manual-override");
            span.setStatus(StatusCode.ERROR, "boom");
            span.end();

            assertThat(h.exporter().getFinishedSpanItems().get(0).getAttributes().get(PLATFORM_REMOTE_SERVICE_KEY))
                    .isEqualTo("manual-override");
        }
    }

    @Test
    void platform_result_не_перезаписывается_если_уже_задан_приложением() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new EnrichingSpanProcessor())) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").setSpanKind(SpanKind.SERVER).startSpan();
            span.setAttribute(PlatformAttributes.PLATFORM_RESULT, "partial");
            span.setStatus(StatusCode.ERROR, "boom");
            span.end();

            assertThat(h.exporter().getFinishedSpanItems().get(0).getAttributes().get(PLATFORM_RESULT_KEY))
                    .isEqualTo("partial");
        }
    }

    @Test
    void DEFAULT_REMOTE_SERVICE_PRIORITY_совпадает_с_контрактом_семантических_конвенций() {
        assertThat(EnrichingSpanProcessor.DEFAULT_REMOTE_SERVICE_PRIORITY)
                .containsExactly("peer.service", "rpc.service", "server.address");
    }

    @Test
    void пустой_приоритет_приводит_к_отсутствию_извлечения_имени() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new EnrichingSpanProcessor(List.of()))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("client").setSpanKind(SpanKind.CLIENT).startSpan();
            span.setAttribute("peer.service", "billing");
            span.setStatus(StatusCode.ERROR, "boom");
            span.end();

            assertThat(h.exporter().getFinishedSpanItems().get(0).getAttributes().get(PLATFORM_REMOTE_SERVICE_KEY))
                    .isNull();
        }
    }
}
