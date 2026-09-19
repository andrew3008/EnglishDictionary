package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

/**
 * Дополнительные кейсы {@link EnrichingSpanProcessor}: мэппинг {@link SpanKind} в категории
 * и запрет перезаписи уже объявленных platform-атрибутов.
 * <p>
 * Здесь каждый тест требует своей конфигурации процессора, поэтому используется
 * {@link SpanProcessorHarness} в {@code try-with-resources}, а не общий
 * {@code @RegisterExtension static OtelSdkExtension}.
 */
class EnrichingSpanProcessorAdvancedTest {

    private static final AttributeKey<String> PLATFORM_TYPE_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_TYPE);
    private static final AttributeKey<String> PLATFORM_RESULT_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT);
    private static final AttributeKey<String> PLATFORM_REMOTE_SERVICE_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_REMOTE_SERVICE);

    @Test
    void INTERNAL_kind_gets_internal_category() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new EnrichingSpanProcessor())) {
            // when
            h.tracer("t").spanBuilder("internal-op").setSpanKind(SpanKind.INTERNAL).startSpan().end();
            // then
            assertThat(h.exporter().getFinishedSpanItems().getFirst().getAttributes().get(PLATFORM_TYPE_KEY))
                    .isEqualTo("internal");
        }
    }

    @Test
    void PRODUCER_and_CONSUMER_kind_get_kafka_categories() {
        // given
        // единая категория rpc разделена — PRODUCER→kafka_producer, CONSUMER→kafka_consumer.
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new EnrichingSpanProcessor())) {
            Tracer tracer = h.tracer("t");
            tracer.spanBuilder("p").setSpanKind(SpanKind.PRODUCER).startSpan().end();
            // when
            tracer.spanBuilder("c").setSpanKind(SpanKind.CONSUMER).startSpan().end();

            // then
            assertThat(h.exporter().getFinishedSpanItems().get(0).getAttributes().get(PLATFORM_TYPE_KEY)).isEqualTo("kafka_producer");
            assertThat(h.exporter().getFinishedSpanItems().get(1).getAttributes().get(PLATFORM_TYPE_KEY)).isEqualTo("kafka_consumer");
        }
    }

    @Test
    void platform_remote_service_does_not_overwrite_pre_set_value() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new EnrichingSpanProcessor())) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("client").setSpanKind(SpanKind.CLIENT).startSpan();
            span.setAttribute("peer.service", "auto-extracted");
            span.setAttribute(PlatformAttributes.PLATFORM_REMOTE_SERVICE, "manual-override");
            span.setStatus(StatusCode.ERROR, "boom");
            // when
            span.end();

            // then
            assertThat(h.exporter().getFinishedSpanItems().getFirst().getAttributes().get(PLATFORM_REMOTE_SERVICE_KEY))
                    .isEqualTo("manual-override");
        }
    }

    @Test
    void platform_result_is_not_overwritten_if_already_set_by_application() {
        // given
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new EnrichingSpanProcessor())) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").setSpanKind(SpanKind.SERVER).startSpan();
            span.setAttribute(PlatformAttributes.PLATFORM_RESULT, "partial");
            span.setStatus(StatusCode.ERROR, "boom");
            // when
            span.end();

            // then
            assertThat(h.exporter().getFinishedSpanItems().getFirst().getAttributes().get(PLATFORM_RESULT_KEY))
                    .isEqualTo("partial");
        }
    }
}
