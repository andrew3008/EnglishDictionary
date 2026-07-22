package space.br1440.platform.tracing.otel.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import space.br1440.platform.tracing.api.CorrelationScope;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntimeFactory;

class CorrelationScopeContractTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private OpenTelemetrySdk openTelemetry;
    private TraceOperations operations;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        operations = new DefaultTraceOperations(OtelTracingRuntimeFactory.create(openTelemetry));
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        tracerProvider.shutdown();
    }

    @Test
    void nestedScopesRestoreIdentityAndMdcInLifoOrder() {
        assertThat(operations.traceContext().correlationId()).isEmpty();

        try (CorrelationScope outer = operations.openCorrelationScope("OUTER")) {
            assertCurrentCorrelation("OUTER");
            try (CorrelationScope inner = operations.openCorrelationScope("INNER")) {
                assertCurrentCorrelation("INNER");
            }
            assertCurrentCorrelation("OUTER");
        }

        assertThat(operations.traceContext().correlationId()).isEmpty();
        assertThat(MDC.get(TracingMdcKeys.CORRELATION_ID)).isNull();
    }

    @Test
    void closeIsIdempotentAndOutOfOrderCloseIsRejectedWithoutMutation() {
        CorrelationScope outer = operations.openCorrelationScope("OUTER");
        CorrelationScope inner = operations.openCorrelationScope("INNER");

        assertThatThrownBy(outer::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LIFO");
        assertCurrentCorrelation("INNER");

        inner.close();
        outer.close();
        outer.close();
        assertThat(operations.traceContext().correlationId()).isEmpty();
    }

    @Test
    void invalidAndNullInputsFailBeforeMutation() {
        try (CorrelationScope ignored = operations.openCorrelationScope("BASE")) {
            assertThatThrownBy(() -> operations.openCorrelationScope(" invalid "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> operations.openCorrelationScope(null))
                    .isInstanceOf(NullPointerException.class);
            assertCurrentCorrelation("BASE");
        }
    }

    @Test
    void helpersExecuteOnceAndPreserveExceptionIdentity() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String result = operations.withCorrelationId("CALL", () -> {
            calls.incrementAndGet();
            assertCurrentCorrelation("CALL");
            return "result";
        });

        IOException checked = new IOException("checked");
        assertThatThrownBy(() -> operations.withCorrelationId("FAIL", () -> {
            throw checked;
        })).isSameAs(checked);

        assertThat(result).isEqualTo("result");
        assertThat(calls).hasValue(1);
        assertThat(operations.traceContext().correlationId()).isEmpty();
    }

    @Test
    void crossThreadCloseFailsAndOwnerCanStillClose() {
        CorrelationScope scope = operations.openCorrelationScope("OWNER");

        assertThatThrownBy(() -> CompletableFuture.runAsync(scope::close).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertCurrentCorrelation("OWNER");

        scope.close();
        assertThat(operations.traceContext().correlationId()).isEmpty();
    }

    @Test
    void deferredResultDoesNotInheritSynchronousScope() throws Exception {
        CompletableFuture<String> deferred = operations.withCorrelationId(
                "SYNC",
                () -> CompletableFuture.supplyAsync(
                        () -> operations.traceContext().correlationId().orElse("none")));

        assertThat(deferred.join()).isEqualTo("none");
    }

    @Test
    void disabledFacadeKeepsIdentityFunctionalWithoutBaggageEmission() {
        TraceOperations disabled = NoopTraceOperations.backedBy(
                NoOpTracingRuntime.disabledByConfiguration("test"));

        disabled.withCorrelationId("DISABLED", () -> {
            assertThat(disabled.traceContext().correlationId()).contains("DISABLED");
            assertThat(io.opentelemetry.api.baggage.Baggage.current()
                    .getEntryValue("platform.correlation.id")).isNull();
        });

        assertThat(disabled.traceContext().correlationId()).isEmpty();
    }

    @Test
    void lateAssignmentLeavesParentImmutableAndChildReceivesValueAtBirth() {
        Span parent = openTelemetry.getTracer("test").spanBuilder("parent").startSpan();
        try (Scope ignored = parent.makeCurrent();
             CorrelationScope correlation = operations.openCorrelationScope("BUSINESS")) {
            operations.spans().operation("child").run(() -> { });
        } finally {
            parent.end();
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData parentData = spans.stream().filter(span -> span.getName().equals("parent")).findFirst().orElseThrow();
        SpanData childData = spans.stream().filter(span -> span.getName().equals("child")).findFirst().orElseThrow();
        AttributeKey<String> key = AttributeKey.stringKey(PlatformAttributes.PLATFORM_CORRELATION_ID);

        assertThat(parentData.getAttributes().get(key)).isNull();
        assertThat(childData.getAttributes().get(key)).isEqualTo("BUSINESS");
    }

    private void assertCurrentCorrelation(String expected) {
        assertThat(operations.traceContext().correlationId()).contains(expected);
        assertThat(MDC.get(TracingMdcKeys.CORRELATION_ID)).isEqualTo(expected);
    }
}
