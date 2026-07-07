package space.br1440.platform.tracing.core;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.propagation.PlatformContextPropagation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OtelPlatformContextPropagationTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private PlatformTracing tracing;
    private PlatformContextPropagation propagation;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracing = new DefaultPlatformTracing(
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());
        propagation = new OtelPlatformContextPropagation();
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        tracerProvider.shutdown();
    }

    @Test
    void wrapRunnable_переносит_traceId_в_completableFuture() throws Exception {
        try (var parent = tracing.manual().operation("parent").start()) {
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
    void runnable_без_wrap_теряет_traceId_в_completableFuture() throws Exception {
        try (var parent = tracing.manual().operation("parent").start()) {
            AtomicReference<String> captured = new AtomicReference<>();

            CompletableFuture.runAsync(() ->
                    captured.set(Span.current().getSpanContext().getTraceId()), executor)
                    .get(5, TimeUnit.SECONDS);

            assertThat(captured.get()).isEqualTo("00000000000000000000000000000000");
        }
    }

    @Test
    void wrapThrowingSupplier_переносит_traceId_и_возвращает_значение() throws Exception {
        try (var parent = tracing.manual().operation("parent").start()) {
            String parentTraceId = tracing.traceContext().traceId().orElseThrow();

            var wrapped = propagation.wrap(
                    (space.br1440.platform.tracing.api.util.ThrowingSupplier<String>)
                            () -> Span.current().getSpanContext().getTraceId());

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
    void wrapThrowingSupplier_корректно_пробрасывает_checked_исключение() {
        try (var parent = tracing.manual().operation("parent").start()) {
            var wrapped = propagation.wrap((space.br1440.platform.tracing.api.util.ThrowingSupplier<String>) () -> {
                throw new Exception("checked failure");
            });

            assertThat(catchException(() -> wrapped.get()))
                    .isInstanceOf(Exception.class)
                    .hasMessage("checked failure");
        }
    }

    @Test
    void contextAwareExecutor_переносит_traceId_на_каждом_submit() throws Exception {
        ExecutorService aware = (ExecutorService) propagation.contextAware(executor);

        AtomicReference<String> firstCapture = new AtomicReference<>();
        try (var first = tracing.manual().operation("first").start()) {
            String firstTrace = tracing.traceContext().traceId().orElseThrow();
            aware.submit(() -> firstCapture.set(Span.current().getSpanContext().getTraceId()))
                    .get(5, TimeUnit.SECONDS);
            assertThat(firstCapture.get()).isEqualTo(firstTrace);
        }

        AtomicReference<String> secondCapture = new AtomicReference<>();
        try (var second = tracing.manual().operation("second").start()) {
            String secondTrace = tracing.traceContext().traceId().orElseThrow();
            aware.submit(() -> secondCapture.set(Span.current().getSpanContext().getTraceId()))
                    .get(5, TimeUnit.SECONDS);
            assertThat(secondCapture.get()).isEqualTo(secondTrace);
        }

        assertThat(firstCapture.get()).isNotEqualTo(secondCapture.get());
    }

    @Test
    void wrap_внутри_nested_spans_сохраняет_child_контекст_а_не_parent() throws Exception {
        try (var parent = tracing.manual().operation("parent").start()) {
            try (var child = tracing.manual().operation("child").child().start()) {
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
