package space.br1440.platform.tracing.otel.span.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.api.attributes.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.builder.ManualSpanBuilder;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidationMetrics;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;
import space.br1440.platform.tracing.otel.runtime.RecordingTracingRuntime;
import space.br1440.platform.tracing.otel.span.DefaultSpanFactory;

class ManualSpanBuilderRelationshipContractTest {

    private static final RemoteSpanLink VALID_LINK = RemoteSpanLink.sampled(
            "0102030405060708090a0b0c0d0e0f10",
            "0102030405060708"
    );

    private RecordingTracingRuntime recording;
    private SpanAttributeValidator policy;

    @BeforeEach
    void setUp() {
        recording = new RecordingTracingRuntime();
        policy = new SpanAttributeValidator(SpanAttributeValidationLevel.WARNING, SpanAttributeValidationMetrics.NOOP);
    }

    static Stream<ManualSpanBuilder<?>> builders() {
        RecordingTracingRuntime impl = new RecordingTracingRuntime();
        SpanAttributeValidator policy = new SpanAttributeValidator(SpanAttributeValidationLevel.WARNING, SpanAttributeValidationMetrics.NOOP);
        DefaultSpanFactory manual = new DefaultSpanFactory(impl, policy);
        var transport = manual.transport();
        var rpc = transport.rpc();
        return Stream.of(
                manual.operation("op"),
                transport.http().server().method("GET").route("/api"),
                transport.database()
                        .system("postgresql")
                        .operation("SELECT")
                        .collection("orders"),
                rpc.server()
                        .system("grpc")
                        .service("OrderService")
                        .method("CreateOrder"));
    }

    @ParameterizedTest
    @MethodSource("builders")
    void repeatedExplicitRelationship_throws(ManualSpanBuilder<?> builder) {
        builder.child();
        assertThatThrownBy(builder::root)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("relationship already set");
    }

    @ParameterizedTest
    @MethodSource("builders")
    void noExplicitRelationship_defaultsToChild(ManualSpanBuilder<?> builder) {
        assertThatCode(() -> {
            try (SpanHandle handle = builder.start()) {
                assertThat(handle).isNotNull();
            }
        }).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("builders")
    void linkedToThenRoot_remainsValid(ManualSpanBuilder<?> builder) {
        assertThatCode(() -> builder.linkedTo(VALID_LINK).root())
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("builders")
    void linkedToThenDetached_failsAtFinalRelationshipLinkValidation(ManualSpanBuilder<?> builder) {
        builder.linkedTo(VALID_LINK);
        assertThatThrownBy(() -> {
            try (SpanHandle handle = builder.detached().start()) {
                fail("linkedTo+detached must fail at start(), got " + handle);
            }
        }).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @MethodSource("builders")
    void linkedToThenChild_failsAtFinalRelationshipLinkValidation(ManualSpanBuilder<?> builder) {
        builder.linkedTo(VALID_LINK);
        assertThatThrownBy(() -> {
            try (SpanHandle handle = builder.child().start()) {
                fail("linkedTo+child must fail at start(), got " + handle);
            }
        }).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void linkedToThenRootThenStart_succeedsForOperationBuilder() {
        assertThatCode(() -> {
            try (SpanHandle handle = new DefaultSpanFactory(recording, policy).operation("op")
                    .linkedTo(VALID_LINK)
                    .root()
                    .start()) {
                assertThat(handle).isNotNull();
            }
        }).doesNotThrowAnyException();
    }
}
