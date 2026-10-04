package space.br1440.platform.tracing.autoconfigure.integrations.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.span.enrich.SpanEnricher;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.javaagent.processor.RequestIdPropagatingSpanProcessor;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntimeFactory;
import space.br1440.platform.tracing.otel.span.enrich.DefaultSpanEnricher;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

class KafkaRequestIdentityOwnershipTest {

    private static final AttributeKey<String> REQUEST_ID = AttributeKey.stringKey(PlatformAttributes.PLATFORM_REQUEST_ID);

    private InMemorySpanExporter exporter;
    private OpenTelemetrySdk sdk;
    private TracingRuntime runtime;
    private KafkaRequestIdentityAspect aspect;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(new RequestIdPropagatingSpanProcessor())
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build())
                .build();
        runtime = OtelTracingRuntimeFactory.create(sdk);
        aspect = aspect(new DefaultSpanEnricher());
    }

    @AfterEach
    void tearDown() {
        sdk.close();
    }

    @Test
    void replacesInheritedRequestIdAndPropagatesCanonicalIdToChild() throws Throwable {
        // given
        Tracer tracer = sdk.getTracer("kafka-ownership");
        Span parent = tracer.spanBuilder("branch").startSpan();
        parent.setAttribute(REQUEST_ID, "request-a");
        Span processing = tracer.spanBuilder("processing").setParent(Context.root().with(parent)).startSpan();
        String traceId = processing.getSpanContext().getTraceId();
        String spanId = processing.getSpanContext().getSpanId();
        ConsumerRecord<String, String> record = record("payload");
        record.headers().add(PlatformHeaders.X_REQUEST_ID, bytes("request-b"));
        assertThat(((ReadableSpan) processing).getAttribute(REQUEST_ID)).isEqualTo("request-a");

        // when
        try (Scope ignored1 = parent.makeCurrent();
             Scope ignored = processing.makeCurrent()) {
            aspect.bindRequestIdentity(joinPoint(record, () -> {
                assertThat(processing.getSpanContext().getTraceId()).isEqualTo(traceId);
                assertThat(processing.getSpanContext().getSpanId()).isEqualTo(spanId);
                assertThat(runtime.currentRequestId()).contains("request-b");
                Span child = tracer.spanBuilder("processing-child").startSpan();
                child.end();
                return "ok";
            }));
        }
        processing.end();
        parent.end();

        // then
        assertThat(attribute("processing")).isEqualTo("request-b");
        assertThat(attribute("processing-child")).isEqualTo("request-b");
        assertThat(attribute("branch")).isEqualTo("request-a");
    }

    @Test
    void nextRecordOnTheSameThreadReceivesItsOwnRequestId() throws Throwable {
        // given
        Tracer tracer = sdk.getTracer("kafka-ownership");
        Span parent = tracer.spanBuilder("shared-branch").startSpan();
        parent.setAttribute(REQUEST_ID, "request-a");
        ConsumerRecord<String, String> first = record("first");
        ConsumerRecord<String, String> second = record("second");
        first.headers().add(PlatformHeaders.X_REQUEST_ID, bytes("request-b"));
        second.headers().add(PlatformHeaders.X_REQUEST_ID, bytes("request-c"));

        // when
        try (Scope ignored = parent.makeCurrent()) {
            invokeProcessing(tracer, "processing-b", first);
            invokeProcessing(tracer, "processing-c", second);
        }
        parent.end();

        // then
        assertThat(attribute("processing-b")).isEqualTo("request-b");
        assertThat(attribute("processing-c")).isEqualTo("request-c");
        assertThat(runtime.currentRequestId()).isEmpty();
    }

    @Test
    void batchDoesNotWriteOneRecordIdOntoCurrentSpan() throws Throwable {
        // given
        Tracer tracer = sdk.getTracer("kafka-ownership");
        Span batch = tracer.spanBuilder("batch").startSpan();
        batch.setAttribute(REQUEST_ID, "request-a");
        ConsumerRecord<String, String> first = record("first");
        ConsumerRecord<String, String> second = record("second");
        first.headers().add(PlatformHeaders.X_REQUEST_ID, bytes("request-b"));
        second.headers().add(PlatformHeaders.X_REQUEST_ID, bytes("request-c"));

        // when
        try (Scope ignored = batch.makeCurrent()) {
            aspect.bindRequestIdentity(joinPoint(new Object[]{first, second}, () -> "batch"));
        }
        batch.end();

        // then
        assertThat(attribute("batch")).isEqualTo("request-a");
        assertThat(runtime.currentRequestId()).isEmpty();
    }

    @Test
    void nonRecordingSpanLeavesListenerResultUnchanged() throws Throwable {
        // given
        InMemorySpanExporter dropped = InMemorySpanExporter.create();
        try (OpenTelemetrySdk unsampled = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .setSampler(Sampler.alwaysOff())
                        .addSpanProcessor(SimpleSpanProcessor.create(dropped))
                        .build())
                .build()) {
            TracingRuntime unsampledRuntime = OtelTracingRuntimeFactory.create(unsampled);
            KafkaRequestIdentityAspect unsampledAspect = new KafkaRequestIdentityAspect(
                    new RequestIdentityBoundarySupport(unsampledRuntime),
                    new DefaultSpanEnricher());
            Span span = unsampled.getTracer("kafka-ownership").spanBuilder("dropped").startSpan();
            ConsumerRecord<String, String> record = record("dropped");
            record.headers().add(PlatformHeaders.X_REQUEST_ID, bytes("request-b"));

            // when
            Object result;
            try (Scope ignored = span.makeCurrent()) {
                assertThat(span.isRecording()).isFalse();
                result = unsampledAspect.bindRequestIdentity(joinPoint(record, () -> "kept"));
            }
            span.end();

            // then
            assertThat(result).isEqualTo("kept");
            assertThat(unsampledRuntime.currentRequestId()).isEmpty();
        }
    }

    @Test
    void enrichmentFailureDoesNotReplaceListenerResult() throws Throwable {
        // given
        SpanEnricher failing = enrichment -> {
            throw new IllegalStateException("enrichment failed");
        };
        KafkaRequestIdentityAspect failingAspect = aspect(failing);
        ConsumerRecord<String, String> record = record("value");
        record.headers().add(PlatformHeaders.X_REQUEST_ID, bytes("request-b"));

        // when
        Object result = failingAspect.bindRequestIdentity(joinPoint(record, () -> "kept"));

        // then
        assertThat(result).isEqualTo("kept");
    }

    @Test
    void listenerFailurePropagatesAfterEnrichment() {
        // given
        ConsumerRecord<String, String> record = record("value");
        record.headers().add(PlatformHeaders.X_REQUEST_ID, bytes("request-b"));
        ProceedingJoinPoint joinPoint = joinPoint(record, () -> {
            throw new IllegalStateException("listener failed");
        });

        // when
        // then
        assertThatThrownBy(() -> aspect.bindRequestIdentity(joinPoint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("listener failed");
    }

    private void invokeProcessing(Tracer tracer, String spanName, ConsumerRecord<String, String> record) throws Throwable {
        Span processing = tracer.spanBuilder(spanName).startSpan();
        try (Scope ignored = processing.makeCurrent()) {
            aspect.bindRequestIdentity(joinPoint(record, () -> null));
        }
        processing.end();
    }

    private KafkaRequestIdentityAspect aspect(SpanEnricher spanEnricher) {
        return new KafkaRequestIdentityAspect(new RequestIdentityBoundarySupport(runtime), spanEnricher);
    }

    private String attribute(String name) {
        return exporter.getFinishedSpanItems().stream()
                .filter(span -> name.equals(span.getName()))
                .map(SpanData::getAttributes)
                .map(attributes -> Objects.requireNonNull(attributes.get(REQUEST_ID)))
                .findFirst()
                .orElseThrow();
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("orders", 0, 1L, "key", value);
    }

    private static ProceedingJoinPoint joinPoint(Object argument, ThrowingAction action) {
        return joinPoint(new Object[]{argument}, action);
    }

    private static ProceedingJoinPoint joinPoint(Object[] arguments, ThrowingAction action) {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(arguments);
        try {
            when(joinPoint.proceed()).thenAnswer(ignored -> action.run());
        } catch (Throwable impossible) {
            throw new AssertionError(impossible);
        }
        return joinPoint;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        Object run() throws Throwable;
    }
}
