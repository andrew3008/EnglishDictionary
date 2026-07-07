package space.br1440.platform.tracing.core.manual;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.api.span.spec.Topology;
import space.br1440.platform.tracing.core.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.impl.RecordingTracingImplementation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 3A hard gate: {@code manual().operation(...)} builder behavior.
 */
class OperationSpanBuilderTest {

    private RecordingTracingImplementation recording;
    private DefaultPlatformTracing tracing;

    @BeforeEach
    void setUp() {
        recording = new RecordingTracingImplementation();
        tracing = new DefaultPlatformTracing(recording);
    }

    @Test
    void start_routesThroughTracingImplementation() {
        tracing.manual().operation("checkout").start().close();
        assertSingleInternalSpec("checkout", Topology.CHILD);
    }

    @Test
    void run_startsAndClosesSpan() {
        tracing.manual().operation("run-op").run(() -> {
        });
        assertSingleInternalSpec("run-op", Topology.CHILD);
    }

    @Test
    void call_returnsValueAndClosesSpan() {
        String result = tracing.manual().operation("call-op").call(() -> "ok");
        assertThat(result).isEqualTo("ok");
        assertSingleInternalSpec("call-op", Topology.CHILD);
    }

    @Test
    void callChecked_propagatesCheckedExceptionAndClosesSpan() throws Exception {
        assertThatThrownBy(() ->
                tracing.manual().operation("checked-op").callChecked(() -> {
                    throw new Exception("checked");
                }))
                .isInstanceOf(Exception.class)
                .hasMessage("checked");
        assertSingleInternalSpec("checked-op", Topology.CHILD);
    }

    @Test
    void nullName_rejected() {
        assertThatThrownBy(() -> tracing.manual().operation(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    @Test
    void blankName_rejected() {
        assertThatThrownBy(() -> tracing.manual().operation("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void operationName_appearsInSpanSpec() {
        tracing.manual().operation("my-operation").start().close();
        assertThat(recording.receivedSpecs().getFirst().name()).isEqualTo("my-operation");
    }

    @Test
    void category_isInternalWithReason() {
        tracing.manual().operation("internal-op").start().close();
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.INTERNAL);
        assertThat(spec.reason()).isEqualTo(SpanSpecReason.PLATFORM_EDGE_CASE);
    }

    @Test
    void rootTopology_preservedInSpanSpecOptions() {
        tracing.manual().operation("root-op").root().start().close();
        assertSingleInternalSpec("root-op", Topology.ROOT);
    }

    @Test
    void childTopology_preservedInSpanSpecOptions() {
        tracing.manual().operation("child-op").child().start().close();
        assertSingleInternalSpec("child-op", Topology.CHILD);
    }

    @Test
    void detachedTopology_preservedInSpanSpecOptions() {
        tracing.manual().operation("detached-op").detached().start().close();
        assertSingleInternalSpec("detached-op", Topology.DETACHED);
    }

    @Test
    void rootWithLinks_preserved() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708");
        tracing.manual().operation("linked-root").root().linkedTo(link).start().close();

        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.options().topology()).isEqualTo(Topology.ROOT);
        assertThat(spec.options().links()).containsExactly(link);
    }

    @Test
    void detachedWithLinks_rejected() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708");
        assertThatThrownBy(() ->
                tracing.manual().operation("bad-detached").detached().linkedTo(link).start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETACHED");
    }

    @Test
    void childWithLinks_rejected() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708");
        assertThatThrownBy(() ->
                tracing.manual().operation("bad-child").child().linkedTo(link).start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHILD");
    }

    private void assertSingleInternalSpec(String name, Topology topology) {
        assertThat(recording.receivedSpecs()).hasSize(1);
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.name()).isEqualTo(name);
        assertThat(spec.category()).isEqualTo(SpanCategory.INTERNAL);
        assertThat(spec.options().topology()).isEqualTo(topology);
    }
}
