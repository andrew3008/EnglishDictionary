package space.br1440.platform.tracing.otel;
import space.br1440.platform.tracing.otel.propagation.OtelPlatformContextPropagation;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntimeFactory;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;
import space.br1440.platform.tracing.api.propagation.PlatformContextPropagation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OtelPlatformContextPropagationTest {

    private SdkTracerProvider tracerProvider;
    private TracingRuntime runtime;
    private TraceOperations tracing;
    private PlatformContextPropagation propagation;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        runtime = OtelTracingRuntimeFactory.create(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());
        tracing = new DefaultTraceOperations(runtime);
        propagation = new OtelPlatformContextPropagation();
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        tracerProvider.shutdown();
        MDC.clear();
    }

    @Test
    void wrapRunnable_propagates_traceId_into_completableFuture() throws Exception {
        try (var parent = tracing.spans().operation("parent").start()) {
            String parentTraceId = tracing.traceContext().traceId().orElseThrow();
            AtomicReference<String> captured = new AtomicReference<>();

            CompletableFuture.runAsync(propagation.wrap(() -> {
                Context.current();
                captured.set(Span.current().getSpanContext().getTraceId());
            }), executor).get(5, TimeUnit.SECONDS);

            assertThat(captured.get()).isEqualTo(parentTraceId);
        }
    }

    @Test
    void runnable_without_wrap_loses_traceId_in_completableFuture() throws Exception {
        try (var parent = tracing.spans().operation("parent").start()) {
            AtomicReference<String> captured = new AtomicReference<>();

            CompletableFuture.runAsync(() ->
                    captured.set(Span.current().getSpanContext().getTraceId()), executor)
                    .get(5, TimeUnit.SECONDS);

            assertThat(captured.get()).isEqualTo("00000000000000000000000000000000");
        }
    }

    @Test
    void wrapThrowingSupplier_propagates_traceId_and_returns_value() throws Exception {
        try (var ignored = tracing.spans().operation("parent").start()) {
            String parentTraceId = tracing.traceContext().traceId().orElseThrow();

            var wrapped = propagation.wrap(() -> Span.current().getSpanContext().getTraceId());

            String supplied = executor.submit(() -> {
                try {
                    return wrapped.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).get(5, TimeUnit.SECONDS);

            assertThat(supplied).isEqualTo(parentTraceId);
        }
    }

    @Test
    void wrapThrowingSupplier_correctly_rethrows_checked_exception() {
        try (var parent = tracing.spans().operation("parent").start()) {
            var wrapped = propagation.wrap((space.br1440.platform.tracing.api.util.ThrowingSupplier<String>) () -> {
                throw new Exception("checked failure");
            });

            assertThat(catchException(wrapped::get))
                    .isInstanceOf(Exception.class)
                    .hasMessage("checked failure");
        }
    }

    @Test
    void contextAwareExecutor_propagates_traceId_on_each_submit() throws Exception {
        ExecutorService aware = (ExecutorService) propagation.contextAware(executor);

        AtomicReference<String> firstCapture = new AtomicReference<>();
        try (var first = tracing.spans().operation("first").start()) {
            String firstTrace = tracing.traceContext().traceId().orElseThrow();
            aware.submit(() -> firstCapture.set(Span.current().getSpanContext().getTraceId()))
                    .get(5, TimeUnit.SECONDS);
            assertThat(firstCapture.get()).isEqualTo(firstTrace);
        }

        AtomicReference<String> secondCapture = new AtomicReference<>();
        try (var second = tracing.spans().operation("second").start()) {
            String secondTrace = tracing.traceContext().traceId().orElseThrow();
            aware.submit(() -> secondCapture.set(Span.current().getSpanContext().getTraceId()))
                    .get(5, TimeUnit.SECONDS);
            assertThat(secondCapture.get()).isEqualTo(secondTrace);
        }

        assertThat(firstCapture.get()).isNotEqualTo(secondCapture.get());
    }

    @Test
    void wrap_inside_nested_spans_keeps_child_context_not_parent() throws Exception {
        try (var parent = tracing.spans().operation("parent").start()) {
            try (var child = tracing.spans().operation("child").child().start()) {
                String childTraceId = tracing.traceContext().traceId().orElseThrow();
                String childSpanId = Span.current().getSpanContext().getSpanId();

                AtomicReference<String> capturedTrace = new AtomicReference<>();
                AtomicReference<String> capturedSpan = new AtomicReference<>();

                CompletableFuture.runAsync(propagation.wrap(() -> {
                    capturedTrace.set(Span.current().getSpanContext().getTraceId());
                    capturedSpan.set(Span.current().getSpanContext().getSpanId());
                }), executor).get(5, TimeUnit.SECONDS);

                assertThat(capturedTrace.get()).isEqualTo(childTraceId);
                assertThat(capturedSpan.get()).isEqualTo(childSpanId);
            }
        }
    }

    @Test
    void wrapRunnable_projects_requestId_and_correlationId_into_mdc() throws Exception {
        // given
        try (CorrelationScope requestScope = runtime.openRequestIdentityScope("request-42");
             CorrelationScope correlationScope = runtime.openCorrelationScope("correlation-42")) {
            AtomicReference<String> capturedRequestId = new AtomicReference<>();
            AtomicReference<String> capturedCorrelationId = new AtomicReference<>();

            // when
            CompletableFuture.runAsync(propagation.wrap(() -> {
                capturedRequestId.set(MDC.get(TracingMdcKeys.REQUEST_ID));
                capturedCorrelationId.set(MDC.get(TracingMdcKeys.CORRELATION_ID));
            }), executor).get(5, TimeUnit.SECONDS);

            // then
            assertThat(capturedRequestId.get()).isEqualTo("request-42");
            assertThat(capturedCorrelationId.get()).isEqualTo("correlation-42");
        }
    }

    @Test
    void wrapCallable_projects_requestId_into_mdc_and_restores_worker_thread_state_after_completion() throws Exception {
        // given
        executor.submit(() -> MDC.put(TracingMdcKeys.REQUEST_ID, "worker-baseline")).get(5, TimeUnit.SECONDS);
        AtomicReference<String> capturedDuring = new AtomicReference<>();

        try (CorrelationScope requestScope = runtime.openRequestIdentityScope("request-77")) {
            var wrapped = propagation.wrap(
                    (space.br1440.platform.tracing.api.util.ThrowingSupplier<String>)
                            () -> MDC.get(TracingMdcKeys.REQUEST_ID));

            // when
            capturedDuring.set(executor.submit(() -> {
                try {
                    return wrapped.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).get(5, TimeUnit.SECONDS));
        }

        String capturedAfter = executor.submit(() -> MDC.get(TracingMdcKeys.REQUEST_ID)).get(5, TimeUnit.SECONDS);

        // then
        assertThat(capturedDuring.get()).isEqualTo("request-77");
        assertThat(capturedAfter).isEqualTo("worker-baseline");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static Throwable catchException(ThrowingRunnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
