package space.br1440.platform.tracing.otel.mdc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityMdcProjectionTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private OpenTelemetrySdk openTelemetry;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        tracerProvider.shutdown();
    }

    @Test
    void projectsAllFiveKeysFromSpanAndIdentity() {
        // given
        Span span = openTelemetry.getTracer("test").spanBuilder("op").startSpan();
        Context identityContext = IdentityMdcProjection.withIdentity(
                Context.current().with(span), "req-1", "corr-1");

        // when
        try (Scope ignored = identityContext.makeCurrent()) {
            IdentityMdcProjection.Snapshot snapshot = IdentityMdcProjection.installCurrent();
            try {
                // then
                assertThat(MDC.get(TracingMdcKeys.TRACE_ID)).isEqualTo(span.getSpanContext().getTraceId());
                assertThat(MDC.get(TracingMdcKeys.SPAN_ID)).isEqualTo(span.getSpanContext().getSpanId());
                assertThat(MDC.get(TracingMdcKeys.TRACE_FLAGS)).isEqualTo(span.getSpanContext().getTraceFlags().asHex());
                assertThat(MDC.get(TracingMdcKeys.REQUEST_ID)).isEqualTo("req-1");
                assertThat(MDC.get(TracingMdcKeys.CORRELATION_ID)).isEqualTo("corr-1");
            } finally {
                snapshot.restore();
            }
        } finally {
            span.end();
        }
    }

    @Test
    void restoresCompositeSnapshotInReverseOrder() {
        // given
        MDC.put(TracingMdcKeys.REQUEST_ID, "outer-request");
        MDC.put(TracingMdcKeys.CORRELATION_ID, "outer-correlation");
        Context identityContext = IdentityMdcProjection.withIdentity(Context.current(), "inner-request", "inner-correlation");

        // when
        try (Scope ignored = identityContext.makeCurrent()) {
            IdentityMdcProjection.Snapshot snapshot = IdentityMdcProjection.installCurrent();
            assertThat(MDC.get(TracingMdcKeys.REQUEST_ID)).isEqualTo("inner-request");

            snapshot.restore();

            // then
            assertThat(MDC.get(TracingMdcKeys.REQUEST_ID)).isEqualTo("outer-request");
            assertThat(MDC.get(TracingMdcKeys.CORRELATION_ID)).isEqualTo("outer-correlation");
            assertThat(MDC.get(TracingMdcKeys.TRACE_ID)).isNull();
        }
    }

    @Test
    void removesSpanKeysButKeepsIdentityKeysWhenSpanIsInvalid() {
        // given
        MDC.put(TracingMdcKeys.TRACE_ID, "stale-trace");
        Context identityContext = IdentityMdcProjection.withIdentity(Context.current(), "req-2", null);

        // when
        try (Scope ignored = identityContext.makeCurrent()) {
            IdentityMdcProjection.Snapshot snapshot = IdentityMdcProjection.installCurrent();
            try {
                // then
                assertThat(MDC.get(TracingMdcKeys.TRACE_ID)).isNull();
                assertThat(MDC.get(TracingMdcKeys.REQUEST_ID)).isEqualTo("req-2");
                assertThat(MDC.get(TracingMdcKeys.CORRELATION_ID)).isNull();
            } finally {
                snapshot.restore();
            }
        }

        assertThat(MDC.get(TracingMdcKeys.TRACE_ID)).isEqualTo("stale-trace");
    }

    @Test
    void currentRequestIdAndCorrelationIdReadFromContext() {
        // given
        Context identityContext = IdentityMdcProjection.withIdentity(Context.current(), "req-3", "corr-3");

        // when / then
        try (Scope ignored = identityContext.makeCurrent()) {
            assertThat(IdentityMdcProjection.currentRequestId()).contains("req-3");
            assertThat(IdentityMdcProjection.currentCorrelationId()).contains("corr-3");
        }

        assertThat(IdentityMdcProjection.currentRequestId()).isEmpty();
        assertThat(IdentityMdcProjection.currentCorrelationId()).isEmpty();
    }
}
