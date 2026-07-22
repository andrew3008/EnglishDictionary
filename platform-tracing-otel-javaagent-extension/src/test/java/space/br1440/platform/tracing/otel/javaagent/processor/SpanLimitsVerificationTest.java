package space.br1440.platform.tracing.otel.javaagent.processor;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Верификация платформенных SpanLimits и корректности dropped-счётчиков
 * {@link MetricsSpanProcessor}.
 * <p>
 * Лимиты задаются программно (не через autoconfigure), что эквивалентно итоговым значениям
 * после применения {@code PlatformTracingDefaultsProvider}, но изолировано от OTel Java Agent.
 */
class SpanLimitsVerificationTest {

    private SdkTracerProvider tracerProvider;

    @AfterEach
    void tearDown() {
        if (tracerProvider != null) {
            tracerProvider.shutdown();
        }
    }

    private Tracer tracerWithLimits(MetricsSpanProcessor metrics, SpanLimits limits) {
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(metrics)
                .setResource(Resource.empty())
                .setSpanLimits(limits)
                .build();
        return tracerProvider.get("test-tracer");
    }

    @Test
    void превышение_лимита_атрибутов_учитывается_как_dropped() {
        MetricsSpanProcessor metrics = new MetricsSpanProcessor();
        SpanLimits limits = SpanLimits.builder().setMaxNumberOfAttributes(2).build();
        Tracer tracer = tracerWithLimits(metrics, limits);

        Span span = tracer.spanBuilder("op").startSpan();
        span.setAttribute("a", "1");
        span.setAttribute("b", "2");
        span.setAttribute("c", "3"); // сверх лимита → drop
        span.setAttribute("d", "4"); // сверх лимита → drop
        span.end();

        assertThat(metrics.getDroppedAttributes()).isEqualTo(2);
    }

    @Test
    void превышение_лимита_событий_учитывается_как_dropped() {
        MetricsSpanProcessor metrics = new MetricsSpanProcessor();
        SpanLimits limits = SpanLimits.builder().setMaxNumberOfEvents(1).build();
        Tracer tracer = tracerWithLimits(metrics, limits);

        Span span = tracer.spanBuilder("op").startSpan();
        span.addEvent("e1");
        span.addEvent("e2");
        span.addEvent("e3");
        span.end();

        assertThat(metrics.getDroppedEvents()).isEqualTo(2);
    }

    @Test
    void превышение_лимита_links_учитывается_как_dropped() {
        MetricsSpanProcessor metrics = new MetricsSpanProcessor();
        SpanLimits limits = SpanLimits.builder().setMaxNumberOfLinks(1).build();
        Tracer tracer = tracerWithLimits(metrics, limits);

        Span span = tracer.spanBuilder("op")
                .addLink(remoteContext("00000000000000000000000000000001"))
                .addLink(remoteContext("00000000000000000000000000000002"))
                .addLink(remoteContext("00000000000000000000000000000003"))
                .startSpan();
        span.end();

        assertThat(metrics.getDroppedLinks()).isEqualTo(2);
    }

    @Test
    void усечение_значения_атрибута_по_числу_символов() {
        MetricsSpanProcessor metrics = new MetricsSpanProcessor();
        SpanLimits limits = SpanLimits.builder().setMaxAttributeValueLength(5).build();
        Tracer tracer = tracerWithLimits(metrics, limits);

        Span span = tracer.spanBuilder("op").startSpan();
        span.setAttribute("k", "abcdefghij");
        span.end();

        // Лимит — character-based: ровно 5 первых символов сохраняются.
        assertThat(((io.opentelemetry.sdk.trace.ReadableSpan) span)
                .toSpanData().getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("k")))
                .isEqualTo("abcde");
    }

    @Test
    void отсутствие_превышения_не_даёт_отрицательных_счётчиков() {
        MetricsSpanProcessor metrics = new MetricsSpanProcessor();
        Tracer tracer = tracerWithLimits(metrics, SpanLimits.getDefault());

        Span span = tracer.spanBuilder("op").startSpan();
        span.setAttribute("a", "1");
        span.end();

        assertThat(metrics.getDroppedAttributes()).isZero();
        assertThat(metrics.getDroppedEvents()).isZero();
        assertThat(metrics.getDroppedLinks()).isZero();
    }

    private static SpanContext remoteContext(String traceId) {
        return SpanContext.createFromRemoteParent(
                traceId,
                "0000000000000001",
                TraceFlags.getSampled(),
                TraceState.getDefault());
    }
}
