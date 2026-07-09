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
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.api.span.spec.Topology;
import space.br1440.platform.tracing.core.runtime.otel.OtelTracingRuntimeFactory;
import space.br1440.platform.tracing.core.facade.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.facade.NoOpPlatformTracing;
import space.br1440.platform.tracing.core.runtime.state.ImmutableTracingState;
import space.br1440.platform.tracing.core.runtime.state.TracingMode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 2 hard gate: all manual span paths route through {@link TracingRuntime#startSpan(SpanSpec)}.
 */
class TracingRuntimeRoutingTest {

    private RecordingTracingRuntime recording;
    private DefaultPlatformTracing tracing;

    @BeforeEach
    void setUp() {
        recording = new RecordingTracingRuntime();
        tracing = new DefaultPlatformTracing(recording);
    }

    @Test
    void operationStart_routesThroughStartSpan() {
        tracing.manual().operation("x").start().close();
        assertSingleSpecNamed("x", Topology.CHILD);
    }

    @Test
    void operationRun_routesThroughStartSpan() {
        tracing.manual().operation("x").run(() -> {
        });
        assertSingleSpecNamed("x", Topology.CHILD);
    }

    @Test
    void operationCall_routesThroughStartSpan() {
        tracing.manual().operation("x").call(() -> "ok");
        assertSingleSpecNamed("x", Topology.CHILD);
    }

    @Test
    void spanFromSpecStart_routesSameSpec() {
        SpanSpec spec = governedSpec("from-spec");
        tracing.manual().spanFromSpec(spec).start().close();
        assertThat(recording.receivedSpecs()).containsExactly(spec);
    }

    @Test
    void spanFromSpecRun_routesSameSpec() {
        SpanSpec spec = governedSpec("from-spec-run");
        tracing.manual().spanFromSpec(spec).run(() -> {
        });
        assertThat(recording.receivedSpecs()).containsExactly(spec);
    }

    @Test
    void topologyRoot_preserved() {
        tracing.manual().operation("root-op").root().start().close();
        assertSingleSpecNamed("root-op", Topology.ROOT);
    }

    @Test
    void topologyDetached_preserved() {
        tracing.manual().operation("detached-op").detached().start().close();
        assertSingleSpecNamed("detached-op", Topology.DETACHED);
    }

    @Test
    void topologyRootWithLinks_preserved() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708");
        tracing.manual().operation("linked-root").root().linkedTo(link).start().close();

        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.name()).isEqualTo("linked-root");
        assertThat(spec.options().topology()).isEqualTo(Topology.ROOT);
        assertThat(spec.options().links()).containsExactly(link);
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
        tracing.manual().spanFromSpec(spec).start().close();
        SpanSpec received = recording.receivedSpecs().getFirst();
        assertThat(received.category()).isEqualTo(SpanCategory.DATABASE);
        assertThat(received.attributes()).containsEntry("db.system",
                space.br1440.platform.tracing.api.span.spec.SpanAttributeValue.of("postgresql"));
        assertThat(received.reason()).isEqualTo(SpanSpecReason.LEGACY_INTEGRATION);
        assertThat(received.reference()).contains("ticket-42");
    }

    @Test
    void noOpFacade_routesThroughBoundary() {
        RecordingTracingRuntime recordingImpl = new RecordingTracingRuntime();
        recordingImpl.setState(ImmutableTracingState.of(
                TracingMode.NOOP, java.util.Optional.empty(), java.util.Map.of()));
        NoOpPlatformTracing noop = NoOpPlatformTracing.backedBy(recordingImpl);
        noop.manual().operation("noop-op").run(() -> {
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
        DefaultPlatformTracing realTracing = new DefaultPlatformTracing(OtelTracingRuntimeFactory.create(OpenTelemetrySdk.builder().setTracerProvider(provider).build()));
        realTracing.setFacadeEnabled(false);
        realTracing.manual().operation("disabled").start().close();
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
        assertThat(realTracing.tracingRuntime().state().mode())
                .isEqualTo(TracingMode.DISABLED_BY_CONFIGURATION);
    }

    private void assertSingleSpecNamed(String name, Topology topology) {
        assertThat(recording.receivedSpecs()).hasSize(1);
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.name()).isEqualTo(name);
        assertThat(spec.options().topology()).isEqualTo(topology);
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
