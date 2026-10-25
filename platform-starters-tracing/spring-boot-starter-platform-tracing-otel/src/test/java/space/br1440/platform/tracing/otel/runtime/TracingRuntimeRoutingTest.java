package space.br1440.platform.tracing.otel.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanRelationship;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecAttributeValue;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

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
    void fromSpecStart_routesNormalizedSpec() {
        SpanSpec spec = governedSpec("from-spec");
        tracing.spans().fromSpec(spec).start().close();
        assertNormalizedFromSpec(spec);
    }

    @Test
    void fromSpecRun_routesNormalizedSpec() {
        SpanSpec spec = governedSpec("from-spec-run");
        tracing.spans().fromSpec(spec).run(() -> {
        });
        assertNormalizedFromSpec(spec);
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
                SpanSpecAttributeValue.of("postgresql"));
        assertThat(received.reason()).contains(SpanSpecReason.LEGACY_INTEGRATION);
        assertThat(received.reference()).contains("ticket-42");
    }

    @Test
    void noOpFacade_keepsManualOperationsNonRecording() {
        NoopTraceOperations noop = NoopTraceOperations.forDisabledRuntime(
                NoOpTracingRuntime.disabledByConfiguration("test-disabled"));
        noop.spans().operation("noop-op").run(() -> {
        });
        assertThat(noop.traceContext().traceId()).isEmpty();
    }

    private void assertSingleSpecNamed(String name, SpanRelationship relationship) {
        assertThat(recording.receivedSpecs()).hasSize(1);
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.name()).isEqualTo(name);
        assertThat(spec.relationship().kind()).isEqualTo(relationship);
        assertThat(spec.category()).isEqualTo(SpanCategory.INTERNAL);
    }

    private void assertNormalizedFromSpec(SpanSpec source) {
        assertThat(source.attributes()).isEmpty();
        assertThat(recording.receivedSpecs()).singleElement().satisfies(received -> {
            assertThat(received.name()).isEqualTo(source.name());
            assertThat(received.category()).isEqualTo(source.category());
            assertThat(received.relationship().kind()).isEqualTo(source.relationship().kind());
            assertThat(received.reason()).isEqualTo(source.reason());
            assertThat(received.attributes()).containsKey(SemconvKeys.PLATFORM_TYPE.getKey());
        });
    }

    private static SpanSpec governedSpec(String name) {
        return SpanSpec.builder(name)
                .category(SpanCategory.INTERNAL)
                .child()
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                .build();
    }
}
