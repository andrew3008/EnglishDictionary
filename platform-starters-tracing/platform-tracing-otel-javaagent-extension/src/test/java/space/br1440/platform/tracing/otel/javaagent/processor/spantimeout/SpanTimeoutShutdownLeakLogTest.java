package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;

class SpanTimeoutShutdownLeakLogTest {

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    @Test
    void shutdownWarnsWhenUnfinishedSpansRemain() {
        // given
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMinutes(1), Duration.ofMinutes(1), System::nanoTime,
                10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("leak");
        Span hung = tracer.spanBuilder("unfinished").startSpan();

        // when
        spanTimeout.shutdown();

        // then
        assertThat(logs.rendered())
                .contains(AgentEventCodes.EXPORT_WATCHDOG)
                .contains("Unfinished spans remain at SDK shutdown");
        hung.end();
        tracerProvider.shutdown();
    }
}
