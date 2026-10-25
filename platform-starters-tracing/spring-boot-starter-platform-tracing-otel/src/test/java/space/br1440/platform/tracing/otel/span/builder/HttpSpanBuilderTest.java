package space.br1440.platform.tracing.otel.span.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.api.attributes.validation.SpanAttributeViolationException;
import space.br1440.platform.tracing.api.semconv.annotation.HttpSemconvVersion;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanFactory;
import space.br1440.platform.tracing.api.span.builder.HttpClientSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.HttpServerSpanBuilder;
import space.br1440.platform.tracing.api.span.builder.HttpTracing;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidationMetrics;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;
import space.br1440.platform.tracing.otel.runtime.RecordingTracingRuntime;
import space.br1440.platform.tracing.otel.span.DefaultSpanFactory;

class HttpSpanBuilderTest {

    private RecordingTracingRuntime recording;
    private SpanFactory manual;

    @BeforeEach
    void setUp() {
        recording = new RecordingTracingRuntime();
        SpanAttributeValidator strictPolicy = new SpanAttributeValidator(
                SpanAttributeValidationLevel.ERROR,
                SpanAttributeValidationMetrics.NOOP
        );

        manual = new DefaultSpanFactory(recording, strictPolicy);
    }

    @Test
    void transportHttp_returnsNonNullEntryPoint() {
        HttpTracing http = manual.transport().http();
        assertThat(http).isNotNull();
        assertThat(http.server()).isNotNull();
        assertThat(http.client()).isNotNull();
    }

    @Test
    void httpBuilders_haveExpectedSemconvVersionMarker() {
        final String expected = "1.28.0";

        assertThat(HttpClientSpanBuilder.class.getAnnotation(HttpSemconvVersion.class))
                .isNotNull()
                .extracting(HttpSemconvVersion::value).isEqualTo(expected);

        assertThat(HttpServerSpanBuilder.class.getAnnotation(HttpSemconvVersion.class))
                .isNotNull()
                .extracting(HttpSemconvVersion::value).isEqualTo(expected);

        assertThat(HttpTracing.class.getAnnotation(HttpSemconvVersion.class)).isNull();
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
        assertThat(spec.reason()).isEmpty();
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
    void semanticBuilder_validatesExactlyOnce() {
        // given
        AtomicInteger violations = new AtomicInteger();
        SpanFactory warningFactory = new DefaultSpanFactory(
                recording,
                new SpanAttributeValidator(
                        SpanAttributeValidationLevel.WARNING,
                        (ruleId, builderName) -> violations.incrementAndGet()
                )
        );

        // when
        warningFactory.transport().http().server().start().close();

        // then
        assertThat(violations).hasValue(1);
    }

    @Test
    void httpServerWithoutMethod_rejectedInErrorMode() {
        assertThatThrownBy(() -> {
            try (SpanHandle handle = manual.transport().http().server().start()) {
                throw new AssertionError("ERROR HTTP server start must fail before returning " + handle);
            }
        }).isInstanceOf(SpanAttributeViolationException.class);
    }

    @Test
    void httpClientWithoutMethod_rejectedInErrorMode() {
        assertThatThrownBy(() -> {
            try (SpanHandle handle = manual.transport().http().client().url("https://example.com").start()) {
                throw new AssertionError("ERROR HTTP client start must fail before returning " + handle);
            }
        }).isInstanceOf(SpanAttributeViolationException.class);
    }

    @Test
    void httpClientBlankUrl_rejected() {
        assertThatThrownBy(() ->
                manual.transport().http().client().method("GET").url("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }
}
