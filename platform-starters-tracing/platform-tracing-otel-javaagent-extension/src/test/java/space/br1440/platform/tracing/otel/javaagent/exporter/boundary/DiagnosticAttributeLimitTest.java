package space.br1440.platform.tracing.otel.javaagent.exporter.boundary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.DiagnosticTextMaskingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOrigin;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuntimeRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;
import space.br1440.platform.tracing.runtime.scrubbing.DiagnosticTextSanitizer;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.data.ExceptionEventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class DiagnosticAttributeLimitTest {

    private static final AttributeKey<String> MESSAGE = AttributeKey.stringKey("exception.message");
    private static final AttributeKey<String> STACK = AttributeKey.stringKey("exception.stacktrace");
    private static final List<SpanAttributeScrubbingRule> RULES = List.of(new DiagnosticTextMaskingRule());

    @ParameterizedTest
    @ValueSource(strings = {"password=canary", "contact@example.com", "eyJheader.payload.signature", "\uD83D\uDE00"})
    void sdkTruncationCannotHideSecretSignatureOrSplitSurrogate(String suffix) {
        // given
        String text = "x".repeat(63) + suffix;
        try (ScrubbingRuntime runtime = BoundaryTestSupport.runtimeOf(RULES, List.of(), null, 64);
             InMemorySpanExporter exporter = InMemorySpanExporter.create();
             SdkTracerProvider provider = provider(runtime, exporter, 64)) {
            Span span = provider.get("test").spanBuilder("safe-operation").startSpan();
            span.setAttribute(MESSAGE, text);
            span.addEvent("exception", Attributes.of(MESSAGE, text));
            span.recordException(new IllegalStateException(text));

            // when
            span.end();
            SpanData result = new SpanDataBoundaryScrubber(runtime).scrub(exporter.getFinishedSpanItems().getFirst()).spanData();

            // then
            assertThat(result.getAttributes().get(MESSAGE)).isNull();
            assertThat(result.getEvents()).allSatisfy(event -> {
                assertThat(event).isNotInstanceOf(ExceptionEventData.class);
                assertThat(event.getAttributes().get(MESSAGE)).isNull();
                assertThat(event.getAttributes().get(STACK)).isNull();
            });
            assertThat(result.getEvents().getLast().getAttributes().get(AttributeKey.stringKey("exception.type")))
                    .isEqualTo(IllegalStateException.class.getName());
        }
    }

    @Test
    void shrinkingUrlMaskCannotRescuePotentiallyTruncatedInput() {
        // given
        String text = "https://host/?token=" + "s".repeat(80);
        try (ScrubbingRuntime runtime = BoundaryTestSupport.runtimeOf(RULES, List.of(), null, 64);
             InMemorySpanExporter exporter = InMemorySpanExporter.create();
             SdkTracerProvider provider = provider(runtime, exporter, 64)) {
            Span span = provider.get("test").spanBuilder("safe-operation").startSpan();
            span.setAttribute(MESSAGE, text).setStatus(StatusCode.ERROR, text);
            span.addEvent("exception", Attributes.of(MESSAGE, text));

            // when
            span.end();
            SpanData result = new SpanDataBoundaryScrubber(runtime).scrub(exporter.getFinishedSpanItems().getFirst()).spanData();

            // then
            assertThat(result.getAttributes().get(MESSAGE)).isNull();
            assertThat(result.getEvents().getFirst().getAttributes().get(MESSAGE)).isNull();
            assertThat(result.getStatus().getDescription()).isEqualTo("https://host/?token=***");
        }
    }

    @ParameterizedTest
    @CsvSource({"true, 64", "false, 3"})
    void finalGuardIncludesCustomHashAndMask(boolean hash, int limit) {
        // given
        SpanAttributeScrubbingRule custom = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return "custom";
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
                return hash ? ScrubbingDecision.hash(name()) : ScrubbingDecision.mask(name());
            }
        };
        try (ScrubbingRuntime runtime = BoundaryTestSupport.runtimeOf(RULES, List.of(custom), "test-key", limit);
             InMemorySpanExporter exporter = InMemorySpanExporter.create();
             SdkTracerProvider provider = provider(runtime, exporter, limit)) {
            Span span = provider.get("test").spanBuilder("safe-operation").startSpan();
            span.setAttribute(MESSAGE, "ok");
            span.addEvent("exception", Attributes.of(MESSAGE, "ok"));

            // when
            span.end();
            SpanData result = new SpanDataBoundaryScrubber(runtime).scrub(exporter.getFinishedSpanItems().getFirst()).spanData();

            // then
            assertThat(result.getAttributes().get(MESSAGE)).isNull();
            assertThat(result.getEvents().getFirst().getAttributes().get(MESSAGE)).isNull();
        }
    }

    @Test
    void collapsedAttributesCannotPublishDiagnosticSentinelsBeyondTheSdkLimit() {
        // given
        Attributes input = Attributes.of(MESSAGE, "raw-message", STACK, "raw-stack",
                AttributeKey.stringKey("detail"), "raw-detail");

        // when
        Attributes collapsed = AttributesScrubber.collapseToSentinel(input);

        // then
        assertThat(collapsed.get(MESSAGE)).isNull();
        assertThat(collapsed.get(STACK)).isNull();
        assertThat(collapsed.get(AttributeKey.stringKey("detail"))).isEqualTo(ScrubbingDecision.SCRUBBING_FAILED_REASON);
    }

    @Test
    void unknownLimitFailsClosedAndKnownUnlimitedStillUsesPolicyBudget() {
        // given
        DiagnosticTextMaskingRule rule = new DiagnosticTextMaskingRule();
        RuntimeRule executable = new RuntimeRule(new ExecutableRule(rule, new RuleCircuitBreaker(rule.name())), RuleOrigin.BUILT_IN);
        try (ScrubbingRuntime runtime = ScrubbingRuntime.create(List.of(executable), 1L, null, false, null)) {
            Attributes input = Attributes.of(MESSAGE, "safe", STACK, "safe");

            // when
            Attributes unknown = AttributesScrubber.scrub(input, runtime).attributes();

            // then
            assertThat(unknown.isEmpty()).isTrue();
            assertThatThrownBy(() -> runtime.bindAttributeValueLengthLimit(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> runtime.bindAttributeValueLengthLimit(-1)).isInstanceOf(IllegalArgumentException.class);

            // when
            runtime.bindAttributeValueLengthLimit(Integer.MAX_VALUE);

            // then
            assertThat(AttributesScrubber.scrub(input, runtime).attributes()).isSameAs(input);
            assertThat(runtime.diagnosticAttributePermitted(MESSAGE.getKey(), "x".repeat(DiagnosticTextSanitizer.MAX_TEXT_LENGTH))).isTrue();
            assertThat(runtime.diagnosticAttributePermitted(MESSAGE.getKey(), "x".repeat(DiagnosticTextSanitizer.MAX_TEXT_LENGTH + 1))).isFalse();
            assertThatThrownBy(() -> runtime.bindAttributeValueLengthLimit(1000)).isInstanceOf(IllegalStateException.class);
        }
    }

    private static SdkTracerProvider provider(ScrubbingRuntime runtime, InMemorySpanExporter exporter, int limit) {
        return SdkTracerProvider.builder()
                .setSpanLimits(SpanLimits.builder().setMaxAttributeValueLength(limit).build())
                .addSpanProcessor(new ScrubbingSpanProcessor(runtime))
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
    }
}
