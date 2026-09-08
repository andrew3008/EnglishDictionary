package space.br1440.platform.tracing.autoconfigure.reactive;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ReactorContextPropagationIntegrationTest {

    private SdkTracerProvider tracerProvider;
    private OpenTelemetry openTelemetry;

    @BeforeEach
    void setUp() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();

        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();

        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    void otelContext_visible_inside_Mono_fromCallable_on_same_thread() {
        Span parent = openTelemetry.getTracer("test").spanBuilder("parent").startSpan();
        String expectedTraceId = parent.getSpanContext().getTraceId();
        try (Scope ignored = parent.makeCurrent()) {
            String traceIdInPipeline = Mono.fromCallable(
                            () -> Span.current().getSpanContext().getTraceId()
                    )
                    .block(Duration.ofSeconds(5));

            assertThat(traceIdInPipeline).isEqualTo(expectedTraceId);
        } finally {
            parent.end();
        }
    }

    @Test
    void schedulers_parallel_available_and_creates_worker_threads_different_from_caller() {
        String callerThread = Thread.currentThread().getName();
        String workerThread = Mono.fromCallable(() -> Thread.currentThread().getName())
                .subscribeOn(Schedulers.parallel())
                .block(Duration.ofSeconds(5));

        assertThat(workerThread).isNotEqualTo(callerThread);
    }
}
