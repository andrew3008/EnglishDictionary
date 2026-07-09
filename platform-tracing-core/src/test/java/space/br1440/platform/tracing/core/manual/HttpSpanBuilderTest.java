package space.br1440.platform.tracing.core.manual;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.manual.HttpTracing;
import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.semconv.SemconvViolationException;
import space.br1440.platform.tracing.api.semconv.ValidationMode;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.core.runtime.RecordingTracingRuntime;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;
import space.br1440.platform.tracing.core.semconv.policy.SemconvMetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 3A hard gate: {@code manual().transport().http()} builder foundation.
 */
class HttpSpanBuilderTest {

    private RecordingTracingRuntime recording;
    private ManualTracing manual;

    @BeforeEach
    void setUp() {
        recording = new RecordingTracingRuntime();
        AttributePolicy strictPolicy = new AttributePolicy(ValidationMode.STRICT, false, SemconvMetrics.NOOP);
        manual = new DefaultManualTracing(recording, strictPolicy);
    }

    @Test
    void transportHttp_returnsNonNullEntryPoint() {
        HttpTracing http = manual.transport().http();
        assertThat(http).isNotNull();
        assertThat(http.server()).isNotNull();
        assertThat(http.client()).isNotNull();
    }

    @Test
    void httpServerStart_routesSpanSpecWithoutDirectOtelUse() {
        manual.transport().http().server()
                .method("GET")
                .route("/api/items")
                .start()
                .close();

        assertThat(recording.receivedSpecs()).hasSize(1);
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.HTTP_SERVER);
        assertThat(spec.reason()).isEqualTo(SpanSpecReason.PLATFORM_EDGE_CASE);
        assertThat(spec.name()).isEqualTo("GET /api/items");
        assertThat(spec.attributes()).containsKey("http.request.method");
        assertThat(spec.attributes()).containsKey("http.route");
    }

    @Test
    void httpClientStart_routesSpanSpecWithoutDirectOtelUse() {
        manual.transport().http().client()
                .method("POST")
                .url("https://example.com/api")
                .start()
                .close();

        assertThat(recording.receivedSpecs()).hasSize(1);
        SpanSpec spec = recording.receivedSpecs().getFirst();
        assertThat(spec.category()).isEqualTo(SpanCategory.HTTP_CLIENT);
        assertThat(spec.name()).isEqualTo("POST");
        assertThat(spec.attributes()).containsKey("http.request.method");
        assertThat(spec.attributes()).containsKey("url.full");
    }

    @Test
    void httpServerWithoutMethod_rejectedInStrictMode() {
        assertThatThrownBy(() ->
                manual.transport().http().server().start())
                .isInstanceOf(SemconvViolationException.class);
    }

    @Test
    void httpClientWithoutMethod_rejectedInStrictMode() {
        assertThatThrownBy(() ->
                manual.transport().http().client().url("https://example.com").start())
                .isInstanceOf(SemconvViolationException.class);
    }

    @Test
    void httpClientBlankUrl_rejected() {
        assertThatThrownBy(() ->
                manual.transport().http().client().method("GET").url("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }
}
