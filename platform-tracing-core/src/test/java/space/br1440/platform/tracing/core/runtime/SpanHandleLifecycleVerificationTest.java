package space.br1440.platform.tracing.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.core.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.core.facade.NoopTraceOperations;
import space.br1440.platform.tracing.core.runtime.otel.OtelTracingRuntimeFactory;

class SpanHandleLifecycleVerificationTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private DefaultTraceOperations tracing;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracing = new DefaultTraceOperations(OtelTracingRuntimeFactory.create(
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()));
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    void explicitHandles_closeIdempotentlyAndRestoreParentContext() {
        SpanHandle parent = tracing.spans().operation("parent").start();
        String parentSpanId = tracing.traceContext().spanId().orElseThrow();
        SpanHandle child = tracing.spans().operation("child").start();

        assertThat(tracing.traceContext().spanId()).hasValueSatisfying(
                childSpanId -> assertThat(childSpanId).isNotEqualTo(parentSpanId));

        child.close();
        child.close();
        assertThat(tracing.traceContext().spanId()).contains(parentSpanId);

        parent.close();
        parent.close();
        assertThat(tracing.traceContext().spanId()).isEmpty();
        assertThat(exporter.getFinishedSpanItems())
                .extracting(span -> span.getName())
                .containsExactly("child", "parent");
    }

    @Test
    void concurrentScopedExecutions_keepContextConfinedToOwningThread() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 8)
                    .<Callable<String>>mapToObj(index -> () -> {
                        String traceId = tracing.spans().operation("task-" + index).call(
                                () -> tracing.traceContext().traceId().orElseThrow());
                        assertThat(tracing.traceContext().traceId()).isEmpty();
                        return traceId;
                    })
                    .toList();

            var traceIds = new HashSet<String>();
            for (var result : executor.invokeAll(tasks)) {
                traceIds.add(result.get());
            }

            assertThat(traceIds).hasSize(8);
            assertThat(exporter.getFinishedSpanItems()).hasSize(8);
            assertThat(tracing.traceContext().traceId()).isEmpty();
        }
    }

    @Test
    void noOpHandle_preservesCloseAndExceptionSafety() {
        var noop = NoopTraceOperations.backedBy(
                NoOpTracingRuntime.disabledByConfiguration("slice-f-verification"));
        SpanHandle handle = noop.spans().operation("noop").start();

        assertThat(handle).isSameAs(NoOpSpanHandle.INSTANCE);
        assertThatCode(() -> {
            handle.recordException(new IllegalStateException("ignored"));
            handle.close();
            handle.close();
        }).doesNotThrowAnyException();
        assertThat(noop.traceContext().traceId()).isEmpty();
    }
}
