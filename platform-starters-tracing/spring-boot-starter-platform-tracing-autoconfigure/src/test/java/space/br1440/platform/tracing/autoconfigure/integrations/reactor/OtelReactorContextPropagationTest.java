package space.br1440.platform.tracing.autoconfigure.integrations.reactor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class OtelReactorContextPropagationTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;
    private OtelReactorContextPropagation propagation;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();

        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();

        tracer = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build()
                .getTracer("otel-reactor-propagation-test");

        Hooks.enableAutomaticContextPropagation();
        propagation = new OtelReactorContextPropagation();
        propagation.afterSingletonsInstantiated();
    }

    @AfterEach
    void tearDown() {
        propagation.destroy();
        tracerProvider.close();
    }

    @Test
    void publishOnPreservesRecordingSpan() {
        // given
        Span inbound = tracer.spanBuilder("inbound").startSpan();

        // when
        String workerTraceId;
        try (Scope ignored = inbound.makeCurrent()) {
            String inboundTraceId = Span.current().getSpanContext().getTraceId();
            workerTraceId = Mono.fromCallable(() -> Span.current().getSpanContext().getTraceId())
                    .publishOn(Schedulers.parallel())
                    .block();
            assertThat(inboundTraceId).isEqualTo(inbound.getSpanContext().getTraceId());
        } finally {
            inbound.end();
        }

        // then
        assertThat(workerTraceId).isEqualTo(inbound.getSpanContext().getTraceId());
    }

    @Test
    void sequentialHopsThenSecondRequestDoNotReuseEndedSpan() {
        // given
        Span first = tracer.spanBuilder("first-request").startSpan();
        String firstTraceId = first.getSpanContext().getTraceId();
        try (Scope ignored = first.makeCurrent()) {
            String afterHops = Mono.fromCallable(() -> "first")
                    .publishOn(Schedulers.parallel())
                    .flatMap(hop -> Mono.fromCallable(() -> Span.current().getSpanContext().getTraceId())
                            .publishOn(Schedulers.parallel()))
                    .block();
            assertThat(afterHops).isEqualTo(firstTraceId);
        }
        first.end();
        OtelReactorContextPropagation.detachEndedCurrentContext();

        // when
        Span second = tracer.spanBuilder("second-request").startSpan();
        String secondTraceId;
        try (Scope ignored = second.makeCurrent()) {
            secondTraceId = Mono.fromCallable(() -> Span.current().getSpanContext().getTraceId())
                    .publishOn(Schedulers.parallel())
                    .block();
        } finally {
            second.end();
        }

        // then
        assertThat(secondTraceId).isEqualTo(second.getSpanContext().getTraceId());
        assertThat(secondTraceId).isNotEqualTo(firstTraceId);
    }

    @Test
    void snapshotRecordingContextReturnsCurrentWhenRecording() {
        // given
        Span inbound = tracer.spanBuilder("snapshot").startSpan();

        // when
        Object snapshot;
        try (Scope ignored = inbound.makeCurrent()) {
            snapshot = OtelReactorContextPropagation.snapshotRecordingContext();
        } finally {
            inbound.end();
        }

        // then
        assertThat(snapshot).isNotNull();
        assertThat(OtelReactorContextPropagation.snapshotRecordingContext()).isNull();
    }

    @Test
    void emptySetValueDoesNotWipeRecordingSpan() {
        // given
        Span inbound = tracer.spanBuilder("inbound-keep").startSpan();

        // when
        String observed;
        try (Scope ignored = inbound.makeCurrent()) {
            OtelContextThreadLocalAccessor accessor = new OtelContextThreadLocalAccessor();
            accessor.setValue();
            observed = Span.current().getSpanContext().getTraceId();
            accessor.restore();
        } finally {
            inbound.end();
        }

        // then
        assertThat(observed).isEqualTo(inbound.getSpanContext().getTraceId());
        assertThat(observed).isNotEqualTo("00000000000000000000000000000000");
    }

    @Test
    void detachEndedCurrentContextClearsNonRecordingSpan() {
        // given
        Span ended = tracer.spanBuilder("ended").startSpan();
        ended.makeCurrent();
        ended.end();
        assertThat(Span.current().getSpanContext().isValid()).isTrue();
        assertThat(Span.current().isRecording()).isFalse();

        // when
        OtelReactorContextPropagation.detachEndedCurrentContext();

        // then
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }
}
