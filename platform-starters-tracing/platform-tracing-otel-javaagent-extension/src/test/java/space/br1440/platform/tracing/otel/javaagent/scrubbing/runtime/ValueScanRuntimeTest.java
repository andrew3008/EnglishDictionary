package space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret;
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;

class ValueScanRuntimeTest {

    private static final SpanAttributeScrubbingRule EMAIL = BuiltInSpanAttributeScrubbingRules.EMAIL.create();
    private static final SpanAttributeScrubbingRule JWT = BuiltInSpanAttributeScrubbingRules.JWT.create();
    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.signature";

    @Test
    void embeddedEmailHashesTheOriginalScalarAndEveryArrayElement() {
        // given
        AttributeKey<String> scalarKey = AttributeKey.stringKey("custom.message");
        AttributeKey<List<String>> arrayKey = AttributeKey.stringArrayKey("custom.values");
        String message = "contact ivan@example.com for support";
        List<String> values = List.of("safe-neighbor", message);
        try (ScrubbingRuntime runtime = ScrubbingRuntimeTestFixtures.fromBuiltInRules(
                List.of(EMAIL), MaskedSecret.of("test-hmac-key"), false);
             SpanProcessorHarness harness = SpanProcessorHarness.of(new ScrubbingSpanProcessor(runtime))) {
            Span span = harness.tracer().spanBuilder("email-values").startSpan();
            span.setAttribute(scalarKey, message);
            span.setAttribute(arrayKey, values);

            // when
            span.end();

            // then
            Attributes output = harness.spans().getFirst().getAttributes();
            assertThat(output.get(scalarKey)).isEqualTo(runtime.hmacHex(message));
            assertThat(output.get(arrayKey)).containsExactly(
                    runtime.hmacHex(values.getFirst()), runtime.hmacHex(values.getLast()));
            assertThat(runtime.hashedActions()).isEqualTo(3L);
            assertThat(runtime.maskedActions()).isZero();
            assertThat(runtime.failures()).isZero();
            assertThat(runtime.attributesVerified()).isTrue();
        }
    }

    @Test
    void missingHmacKeyMasksEveryArrayElementAndCountsTheFallback() {
        // given
        AttributeKey<List<String>> key = AttributeKey.stringArrayKey("custom.values");
        try (ScrubbingRuntime runtime = ScrubbingRuntimeTestFixtures.fromBuiltInRules(List.of(EMAIL));
             SpanProcessorHarness harness = SpanProcessorHarness.of(new ScrubbingSpanProcessor(runtime))) {
            Span span = harness.tracer().spanBuilder("email-without-key").startSpan();
            span.setAttribute(key, List.of("safe-neighbor", "ivan@example.com"));

            // when
            span.end();

            // then
            assertThat(harness.spans().getFirst().getAttributes().get(key)).containsExactly("***", "***");
            assertThat(runtime.maskedActions()).isEqualTo(2L);
            assertThat(runtime.hashMissingKey()).isEqualTo(2L);
            assertThat(runtime.hashedActions()).isZero();
            assertThat(runtime.failures()).isZero();
        }
    }

    @Test
    void embeddedJwtDropsTheOriginalScalarAndEveryArrayElement() {
        // given
        AttributeKey<String> scalarKey = AttributeKey.stringKey("custom.message");
        AttributeKey<List<String>> arrayKey = AttributeKey.stringArrayKey("custom.values");
        try (ScrubbingRuntime runtime = ScrubbingRuntimeTestFixtures.fromBuiltInRules(
                List.of(EMAIL, JWT), MaskedSecret.of("test-hmac-key"), false);
             SpanProcessorHarness harness = SpanProcessorHarness.of(new ScrubbingSpanProcessor(runtime))) {
            Span span = harness.tracer().spanBuilder("jwt-values").startSpan();
            span.setAttribute(scalarKey, "Authorization: Bearer " + TOKEN);
            span.setAttribute(arrayKey, List.of("ivan@example.com", "token=" + TOKEN + ";"));

            // when
            span.end();

            // then
            Attributes output = harness.spans().getFirst().getAttributes();
            assertThat(output.get(scalarKey)).isEmpty();
            assertThat(output.get(arrayKey)).containsExactly("", "");
            assertThat(runtime.droppedActions()).isEqualTo(3L);
            assertThat(runtime.hashedActions()).isZero();
            assertThat(runtime.maskedActions()).isZero();
            assertThat(runtime.failures()).isZero();
            assertThat(runtime.attributesVerified()).isTrue();
        }
    }

    @ParameterizedTest
    @MethodSource("oversizedArrays")
    void oversizedArrayCollapsesOnceBeforeMatchingOrHashing(List<String> values) {
        // given
        AttributeKey<List<String>> key = AttributeKey.stringArrayKey("custom.values");
        try (ScrubbingRuntime runtime = ScrubbingRuntimeTestFixtures.fromBuiltInRules(
                List.of(EMAIL, JWT), MaskedSecret.of("test-hmac-key"), false);
             SpanProcessorHarness harness = SpanProcessorHarness.of(new ScrubbingSpanProcessor(runtime))) {
            Span span = harness.tracer().spanBuilder("oversized-values").startSpan();
            span.setAttribute(key, values);

            // when
            span.end();

            // then
            assertThat(harness.spans().getFirst().getAttributes().get(key)).containsExactly("***");
            assertThat(runtime.maskedActions()).isEqualTo(1L);
            assertThat(runtime.hashedActions()).isZero();
            assertThat(runtime.hashMissingKey()).isZero();
            assertThat(runtime.droppedActions()).isZero();
            assertThat(runtime.failures()).isZero();
            assertThat(runtime.totalBreakerFailures()).isZero();
            assertThat(runtime.attributesVerified()).isTrue();
            assertThat(runtime.collapseModeActivations()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"custom.message", "hardware.id", "preferred_username"})
    void oversizedScalarWithoutSecretMarkersIsMaskedBeforeKeyBasedHash(String attributeName) {
        // given
        AttributeKey<String> key = AttributeKey.stringKey(attributeName);
        List<SpanAttributeScrubbingRule> rules = List.of(EMAIL, JWT,
                BuiltInSpanAttributeScrubbingRules.HARDWARE_IDENTITY.create(),
                BuiltInSpanAttributeScrubbingRules.USER_IDENTITY.create());
        try (ScrubbingRuntime runtime = ScrubbingRuntimeTestFixtures.fromBuiltInRules(
                rules, MaskedSecret.of("test-hmac-key"), false);
             SpanProcessorHarness harness = SpanProcessorHarness.of(new ScrubbingSpanProcessor(runtime))) {
            Span span = harness.tracer().spanBuilder("oversized-message").startSpan();
            span.setAttribute(key, "x".repeat(4097));

            // when
            span.end();

            // then
            assertThat(harness.spans().getFirst().getAttributes().get(key)).isEqualTo("***");
            assertThat(runtime.maskedActions()).isEqualTo(1L);
            assertThat(runtime.hashedActions()).isZero();
            assertThat(runtime.hashMissingKey()).isZero();
            assertThat(runtime.failures()).isZero();
            assertThat(runtime.attributesVerified()).isTrue();
        }
    }

    @Test
    void valueRulesPreserveNumericAndBooleanArraysBeyondTheStringArrayLimit() {
        // given
        AttributeKey<List<Long>> longKey = AttributeKey.longArrayKey("custom.longs");
        AttributeKey<List<Double>> doubleKey = AttributeKey.doubleArrayKey("custom.doubles");
        AttributeKey<List<Boolean>> booleanKey = AttributeKey.booleanArrayKey("custom.booleans");
        List<Long> longs = Collections.nCopies(33, 42L);
        List<Double> doubles = Collections.nCopies(33, 1.5d);
        List<Boolean> booleans = Collections.nCopies(33, true);
        try (ScrubbingRuntime runtime = ScrubbingRuntimeTestFixtures.fromBuiltInRules(List.of(EMAIL, JWT));
             SpanProcessorHarness harness = SpanProcessorHarness.of(new ScrubbingSpanProcessor(runtime))) {
            Span span = harness.tracer().spanBuilder("typed-values").startSpan();
            span.setAttribute(longKey, longs);
            span.setAttribute(doubleKey, doubles);
            span.setAttribute(booleanKey, booleans);

            // when
            span.end();

            // then
            Attributes output = harness.spans().getFirst().getAttributes();
            assertThat(output.get(longKey)).isEqualTo(longs);
            assertThat(output.get(doubleKey)).isEqualTo(doubles);
            assertThat(output.get(booleanKey)).isEqualTo(booleans);
            assertThat(runtime.maskedActions()).isZero();
            assertThat(runtime.droppedActions()).isZero();
            assertThat(runtime.hashedActions()).isZero();
            assertThat(runtime.failures()).isZero();
        }
    }

    private static Stream<List<String>> oversizedArrays() {
        return Stream.of(
                Collections.nCopies(33, "safe"),
                List.of("ivan@example.com", "x".repeat(4096)),
                List.of(TOKEN, "x".repeat(4096)));
    }
}
