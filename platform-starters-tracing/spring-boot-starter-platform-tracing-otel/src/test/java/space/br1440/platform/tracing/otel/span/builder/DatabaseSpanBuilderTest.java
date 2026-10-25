package space.br1440.platform.tracing.otel.span.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanFactory;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidationMetrics;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;
import space.br1440.platform.tracing.otel.runtime.RecordingTracingRuntime;
import space.br1440.platform.tracing.otel.span.DefaultSpanFactory;

class DatabaseSpanBuilderTest {

    private RecordingTracingRuntime recording;
    private SpanFactory manual;

    @BeforeEach
    void setUp() {
        recording = new RecordingTracingRuntime();
        SpanAttributeValidator strictPolicy = new SpanAttributeValidator(SpanAttributeValidationLevel.ERROR, SpanAttributeValidationMetrics.NOOP);
        manual = new DefaultSpanFactory(recording, strictPolicy);
    }

    @Test
    void validBuilder_routesSpanSpecThroughTracingRuntime() {
        try (SpanHandle handle = manual.transport().database()
                .operation("SELECT")
                .system("postgresql")
                .collection("orders")
                .start()) {
            assertThat(handle).isNotNull();
        }

        assertThat(recording.receivedSpecs()).hasSize(1);
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.DATABASE);
        assertThat(spec.reason()).isEmpty();
        assertThat(spec.name()).isEqualTo("SELECT orders");
        assertThat(spec.attributes()).containsKey("db.operation.name");
        assertThat(spec.attributes()).containsKey("db.system.name");
        assertThat(spec.attributes()).containsKey("db.collection.name");
    }

    @Test
    void missingOperation_rejected() {
        assertThatThrownBy(() -> {
            try (SpanHandle handle = manual.transport().database()
                    .system("postgresql")
                    .collection("orders")
                    .start()) {
                fail("start() must not return " + handle);
            }
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operation");
    }

    @Test
    void missingSystem_rejected() {
        assertThatThrownBy(() -> {
            try (SpanHandle handle = manual.transport().database()
                    .operation("SELECT")
                    .collection("orders")
                    .start()) {
                fail("start() must not return " + handle);
            }
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system");
    }

    @Test
    void missingCollection_rejected() {
        assertThatThrownBy(() -> {
            try (SpanHandle handle = manual.transport().database()
                    .operation("SELECT")
                    .system("postgresql")
                    .start()) {
                fail("start() must not return " + handle);
            }
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collection");
    }

    @Test
    void rootRelationship_works() {
        try (SpanHandle handle = manual.transport().database()
                .operation("INSERT")
                .system("postgresql")
                .collection("orders")
                .root()
                .start()) {
            assertThat(handle).isNotNull();
        }

        assertThat(recording.receivedSpecs().getFirst().relationship().kind())
                .isEqualTo(space.br1440.platform.tracing.api.span.spec.SpanRelationship.ROOT);
    }

    @Test
    void childWithLinks_rejected() {
        RemoteSpanLink link = RemoteSpanLink.sampled(
                "0102030405060708090a0b0c0d0e0f10",
                "0102030405060708");
        assertThatThrownBy(() -> {
            try (SpanHandle handle = manual.transport().database()
                    .operation("SELECT")
                    .system("postgresql")
                    .collection("orders")
                    .child()
                    .linkedTo(link)
                    .start()) {
                fail("start() must not return " + handle);
            }
        })
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
