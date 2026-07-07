package space.br1440.platform.tracing.otel.extension.processor;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsSpanProcessorTest {

    private MetricsSpanProcessor metricsProcessor;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        metricsProcessor = new MetricsSpanProcessor();

        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(metricsProcessor)
                .setResource(Resource.empty())
                .build();

        tracer = tracerProvider.get("test-tracer");
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    void countsEndedSpans() {
        tracer.spanBuilder("op1").startSpan().end();
        tracer.spanBuilder("op2").startSpan().end();
        tracer.spanBuilder("op3").startSpan().end();

        assertThat(metricsProcessor.getEndedSpans()).isEqualTo(3);
        assertThat(metricsProcessor.getErrorSpans()).isEqualTo(0);
        assertThat(metricsProcessor.getTimeoutSpans()).isEqualTo(0);
    }

    @Test
    void countsErrorSpans() {
        io.opentelemetry.api.trace.Span span1 = tracer.spanBuilder("error-op").startSpan();
        span1.setStatus(StatusCode.ERROR);
        span1.end();

        tracer.spanBuilder("ok-op").startSpan().end();

        assertThat(metricsProcessor.getEndedSpans()).isEqualTo(2);
        assertThat(metricsProcessor.getErrorSpans()).isEqualTo(1);
    }

    @Test
    void countsTimeoutSpans() {
        io.opentelemetry.api.trace.Span span1 = tracer.spanBuilder("timeout-op").startSpan();
        span1.setAttribute(PlatformAttributes.PLATFORM_TIMEOUT, "span");
        span1.end();

        assertThat(metricsProcessor.getEndedSpans()).isEqualTo(1);
        assertThat(metricsProcessor.getTimeoutSpans()).isEqualTo(1);
    }
}
