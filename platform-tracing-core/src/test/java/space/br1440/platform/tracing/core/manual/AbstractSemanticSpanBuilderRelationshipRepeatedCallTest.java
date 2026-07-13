package space.br1440.platform.tracing.core.manual;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import space.br1440.platform.tracing.api.manual.ManualSpanBuilder;
import space.br1440.platform.tracing.api.semconv.SemconvValidationMode;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.core.runtime.RecordingTracingRuntime;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;
import space.br1440.platform.tracing.core.semconv.policy.SemconvMetrics;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbstractSemanticSpanBuilderRelationshipRepeatedCallTest {

    private static final RemoteSpanLink VALID_LINK = RemoteSpanLink.sampled(
            "0102030405060708090a0b0c0d0e0f10", "0102030405060708");

    private RecordingTracingRuntime recording;
    private AttributePolicy policy;

    @BeforeEach
    void setUp() {
        recording = new RecordingTracingRuntime();
        policy = new AttributePolicy(SemconvValidationMode.STRICT, false, SemconvMetrics.NOOP);
    }

    static Stream<ManualSpanBuilder<?>> builders() {
        RecordingTracingRuntime impl = new RecordingTracingRuntime();
        AttributePolicy policy = new AttributePolicy(SemconvValidationMode.STRICT, false, SemconvMetrics.NOOP);
        DefaultSpanFactory manual = new DefaultSpanFactory(impl, policy);
        DefaultTransportTracing transport = new DefaultTransportTracing(impl, policy);
        DefaultRpcTracing rpc = new DefaultRpcTracing(impl, policy);
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
        assertThatCode(builder::start).doesNotThrowAnyException();
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
        assertThatThrownBy(() -> builder.detached().start())
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @MethodSource("builders")
    void linkedToThenChild_failsAtFinalRelationshipLinkValidation(ManualSpanBuilder<?> builder) {
        builder.linkedTo(VALID_LINK);
        assertThatThrownBy(() -> builder.child().start())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void linkedToThenRootThenStart_succeedsForOperationBuilder() {
        assertThatCode(() ->
                new DefaultSpanFactory(recording, policy).operation("op")
                        .linkedTo(VALID_LINK)
                        .root()
                        .start()
                        .close())
                .doesNotThrowAnyException();
    }
}
