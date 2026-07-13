package space.br1440.platform.tracing.core.manual;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.SpanFactory;
import space.br1440.platform.tracing.api.semconv.SemconvViolationException;
import space.br1440.platform.tracing.api.semconv.SemconvValidationMode;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.core.runtime.RecordingTracingRuntime;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;
import space.br1440.platform.tracing.core.semconv.policy.SemconvMetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 3B hard gate: {@code spans().transport().database()} builder behavior.
 */
class DatabaseSpanBuilderTest {

    private RecordingTracingRuntime recording;
    private SpanFactory manual;

    @BeforeEach
    void setUp() {
        recording = new RecordingTracingRuntime();
        AttributePolicy strictPolicy = new AttributePolicy(SemconvValidationMode.STRICT, false, SemconvMetrics.NOOP);
        manual = new DefaultSpanFactory(recording, strictPolicy);
    }

    @Test
    void validBuilder_routesSpanSpecThroughTracingRuntime() {
        manual.transport().database()
                .operation("SELECT")
                .system("postgresql")
                .collection("orders")
                .start()
                .close();

        assertThat(recording.receivedSpecs()).hasSize(1);
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.DATABASE);
        assertThat(spec.reason()).isEqualTo(SpanSpecReason.PLATFORM_EDGE_CASE);
        assertThat(spec.name()).isEqualTo("SELECT orders");
        assertThat(spec.attributes()).containsKey("db.operation.name");
        assertThat(spec.attributes()).containsKey("db.system.name");
        assertThat(spec.attributes()).containsKey("db.collection.name");
    }

    @Test
    void missingOperation_rejected() {
        assertThatThrownBy(() ->
                manual.transport().database()
                        .system("postgresql")
                        .collection("orders")
                        .start())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operation");
    }

    @Test
    void missingSystem_rejected() {
        assertThatThrownBy(() ->
                manual.transport().database()
                        .operation("SELECT")
                        .collection("orders")
                        .start())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system");
    }

    @Test
    void missingCollection_rejected() {
        assertThatThrownBy(() ->
                manual.transport().database()
                        .operation("SELECT")
                        .system("postgresql")
                        .start())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collection");
    }

    @Test
    void rootRelationship_works() {
        manual.transport().database()
                .operation("INSERT")
                .system("postgresql")
                .collection("orders")
                .root()
                .start()
                .close();

        assertThat(recording.receivedSpecs().getFirst().relationship().kind())
                .isEqualTo(space.br1440.platform.tracing.api.span.spec.SpanRelationship.ROOT);
    }

    @Test
    void childWithLinks_rejected() {
        RemoteSpanLink link = RemoteSpanLink.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708");
        assertThatThrownBy(() ->
                manual.transport().database()
                        .operation("SELECT")
                        .system("postgresql")
                        .collection("orders")
                        .child()
                        .linkedTo(link)
                        .start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHILD");
    }

    @Test
    void strictMode_missingPlatformTypeInjectedBySemanticSpecs() {
        manual.transport().database()
                .operation("SELECT")
                .system("postgresql")
                .collection("orders")
                .run(() -> {
                });
        assertThat(recording.receivedSpecs()).hasSize(1);
    }
}
