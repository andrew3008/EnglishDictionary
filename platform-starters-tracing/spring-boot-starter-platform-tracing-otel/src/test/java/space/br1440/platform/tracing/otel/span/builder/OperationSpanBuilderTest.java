package space.br1440.platform.tracing.otel.span.builder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.api.span.spec.SpanRelationship;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.runtime.RecordingTracingRuntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hard gate: {@code spans().operation(...)} builder behavior.
 */
class OperationSpanBuilderTest {

    private RecordingTracingRuntime recording;
    private DefaultTraceOperations tracing;

    @BeforeEach
    void setUp() {
        recording = new RecordingTracingRuntime();
        tracing = new DefaultTraceOperations(recording);
    }

    @Test
    void start_routesThroughTracingRuntime() {
        tracing.spans().operation("checkout").start().close();
        assertSingleInternalSpec("checkout", SpanRelationship.CHILD);
    }

    @Test
    void run_startsAndClosesSpan() {
        tracing.spans().operation("run-op").run(() -> {
        });
        assertSingleInternalSpec("run-op", SpanRelationship.CHILD);
    }

    @Test
    void call_returnsValueAndClosesSpan() {
        String result = tracing.spans().operation("call-op").call(() -> "ok");
        assertThat(result).isEqualTo("ok");
        assertSingleInternalSpec("call-op", SpanRelationship.CHILD);
    }

    @Test
    void callChecked_propagatesCheckedExceptionAndClosesSpan() throws Exception {
        assertThatThrownBy(() ->
                tracing.spans().operation("checked-op").callChecked(() -> {
                    throw new Exception("checked");
                }))
                .isInstanceOf(Exception.class)
                .hasMessage("checked");
        assertSingleInternalSpec("checked-op", SpanRelationship.CHILD);
    }

    @Test
    void nullName_rejected() {
        assertThatThrownBy(() -> tracing.spans().operation(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    @Test
    void blankName_rejected() {
        assertThatThrownBy(() -> tracing.spans().operation("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void operationName_appearsInSpanSpec() {
        tracing.spans().operation("my-operation").start().close();
        assertThat(recording.receivedSpecs().getFirst().name()).isEqualTo("my-operation");
    }

    @Test
    void category_isInternalWithReason() {
        tracing.spans().operation("internal-op").start().close();
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.INTERNAL);
        assertThat(spec.reason()).isEqualTo(SpanSpecReason.PLATFORM_EDGE_CASE);
    }

    @Test
    void rootRelationship_preservedInSpec() {
        tracing.spans().operation("root-op").root().start().close();
        assertSingleInternalSpec("root-op", SpanRelationship.ROOT);
    }

    @Test
    void childRelationship_preservedInSpec() {
        tracing.spans().operation("child-op").child().start().close();
        assertSingleInternalSpec("child-op", SpanRelationship.CHILD);
    }

    @Test
    void detachedRelationship_preservedInSpec() {
        tracing.spans().operation("detached-op").detached().start().close();
        assertSingleInternalSpec("detached-op", SpanRelationship.DETACHED);
    }

    @Test
    void rootWithLinks_preserved() {
        RemoteSpanLink link = RemoteSpanLink.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708");
        tracing.spans().operation("linked-root").root().linkedTo(link).start().close();

        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.relationship().kind()).isEqualTo(SpanRelationship.ROOT);
        assertThat(spec.relationship().links()).containsExactly(link);
    }

    @Test
    void detachedWithLinks_rejected() {
        RemoteSpanLink link = RemoteSpanLink.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708");
        assertThatThrownBy(() ->
                tracing.spans().operation("bad-detached").detached().linkedTo(link).start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETACHED");
    }

    @Test
    void childWithLinks_rejected() {
        RemoteSpanLink link = RemoteSpanLink.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708");
        assertThatThrownBy(() ->
                tracing.spans().operation("bad-child").child().linkedTo(link).start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHILD");
    }

    private void assertSingleInternalSpec(String name, SpanRelationship relationship) {
        assertThat(recording.receivedSpecs()).hasSize(1);
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.name()).isEqualTo(name);
        assertThat(spec.category()).isEqualTo(SpanCategory.INTERNAL);
        assertThat(spec.relationship().kind()).isEqualTo(relationship);
    }
}
