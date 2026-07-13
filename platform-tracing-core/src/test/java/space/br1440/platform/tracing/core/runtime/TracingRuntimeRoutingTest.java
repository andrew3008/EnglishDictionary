package space.br1440.platform.tracing.core.runtime;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.api.span.spec.SpanRelationship;
import space.br1440.platform.tracing.core.runtime.otel.OtelTracingRuntimeFactory;
import space.br1440.platform.tracing.core.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.core.facade.NoopTraceOperations;
import space.br1440.platform.tracing.core.runtime.state.ImmutableTracingState;
import space.br1440.platform.tracing.core.runtime.state.TracingMode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 2 hard gate: all manual span paths route through {@link TracingRuntime#startSpan(SpanSpec)}.
 */
class TracingRuntimeRoutingTest {

    private RecordingTracingRuntime recording;
    private DefaultTraceOperations tracing;

    @BeforeEach
    void setUp() {
        recording = new RecordingTracingRuntime();
        tracing = new DefaultTraceOperations(recording);
    }

    @Test
    void operationStart_routesThroughStartSpan() {
        tracing.spans().operation("x").start().close();
        assertSingleSpecNamed("x", SpanRelationship.CHILD);
    }

    @Test
    void operationRun_routesThroughStartSpan() {
        tracing.spans().operation("x").run(() -> {
        });
        assertSingleSpecNamed("x", SpanRelationship.CHILD);
    }

    @Test
    void operationCall_routesThroughStartSpan() {
        tracing.spans().operation("x").call(() -> "ok");
        assertSingleSpecNamed("x", SpanRelationship.CHILD);
    }

    @Test
    void fromSpecStart_routesSameSpec() {
        SpanSpec spec = governedSpec("from-spec");
        tracing.spans().fromSpec(spec).start().close();
        assertThat(recording.receivedSpecs()).containsExactly(spec);
    }

    @Test
    void fromSpecRun_routesSameSpec() {
        SpanSpec spec = governedSpec("from-spec-run");
        tracing.spans().fromSpec(spec).run(() -> {
        });
        assertThat(recording.receivedSpecs()).containsExactly(spec);
    }

    @Test
    void topologyRoot_preserved() {
        tracing.spans().operation("root-op").root().start().close();
        assertSingleSpecNamed("root-op", SpanRelationship.ROOT);
    }

    @Test
    void topologyDetached_preserved() {
        tracing.spans().operation("detached-op").detached().start().close();
        assertSingleSpecNamed("detached-op", SpanRelationship.DETACHED);
    }

    @Test
    void topologyRootWithLinks_preserved() {
        RemoteSpanLink link = RemoteSpanLink.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708");
        tracing.spans().operation("linked-root").root().linkedTo(link).start().close();

        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.name()).isEqualTo("linked-root");
        assertThat(spec.relationship().kind()).isEqualTo(SpanRelationship.ROOT);
        assertThat(spec.relationship().links()).containsExactly(link);
    }

    @Test
    void spanSpecAttributesReasonReference_preserved() {
        SpanSpec spec = SpanSpec.builder("attr-spec")
                .category(SpanCategory.DATABASE)
                .root()
                .attribute("db.system", "postgresql")
                .reason(SpanSpecReason.LEGACY_INTEGRATION)
                .reference("ticket-42")
                .build();
        tracing.spans().fromSpec(spec).start().close();
        SpanSpec received = recording.receivedSpecs().getFirst();
        assertThat(received.category()).isEqualTo(SpanCategory.DATABASE);
        assertThat(received.attributes()).containsEntry("db.system",
                space.br1440.platform.tracing.api.span.spec.SpanSpecAttributeValue.of("postgresql"));
        assertThat(received.reason()).isEqualTo(SpanSpecReason.LEGACY_INTEGRATION);
        assertThat(received.reference()).contains("ticket-42");
    }

    @Test
    void noOpFacade_routesThroughBoundary() {
        RecordingTracingRuntime recordingImpl = new RecordingTracingRuntime();
        recordingImpl.setState(ImmutableTracingState.of(
                TracingMode.NOOP, null, java.util.Map.of()));
        NoopTraceOperations noop = NoopTraceOperations.backedBy(recordingImpl);
        noop.spans().operation("noop-op").run(() -> {
        });
        assertThat(recordingImpl.receivedSpecs()).hasSize(1);
        assertThat(recordingImpl.receivedSpecs().getFirst().name()).isEqualTo("noop-op");
    }

    @Test
    void killSwitchDisabled_routesThroughBoundaryWithNoRealSpan() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        DefaultTraceOperations realTracing = new DefaultTraceOperations(OtelTracingRuntimeFactory.create(OpenTelemetrySdk.builder().setTracerProvider(provider).build()));
        realTracing.setFacadeEnabled(false);
        realTracing.spans().operation("disabled").start().close();
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
        assertThat(realTracing.tracingRuntime().state().mode())
                .isEqualTo(TracingMode.DISABLED_BY_CONFIGURATION);
    }

    private void assertSingleSpecNamed(String name, SpanRelationship relationship) {
        assertThat(recording.receivedSpecs()).hasSize(1);
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.name()).isEqualTo(name);
        assertThat(spec.relationship().kind()).isEqualTo(relationship);
        assertThat(spec.category()).isEqualTo(SpanCategory.INTERNAL);
    }

    private static SpanSpec governedSpec(String name) {
        return SpanSpec.builder(name)
                .category(SpanCategory.INTERNAL)
                .child()
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                .build();
    }
}
