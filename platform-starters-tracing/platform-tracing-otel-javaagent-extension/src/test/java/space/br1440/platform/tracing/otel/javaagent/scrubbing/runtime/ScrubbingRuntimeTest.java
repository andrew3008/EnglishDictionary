package space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.List;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOrigin;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuntimeRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.runtime.scrubbing.DiagnosticTextSanitizer;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.SpanLimits;

class ScrubbingRuntimeTest {

    @ParameterizedTest
    @ValueSource(ints = {-1, 512})
    void urlMaskingSurvivesSubsequentTruncateWithoutLengthChange(int maximumLength) {
        // given
        AttributeKey<String> key = AttributeKey.stringKey("exception.message");
        SpanAttributeScrubbingRule lengthLimit = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return "length-limit";
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String attributeKey, @Nullable Object value) {
                return ScrubbingDecision.truncate(name(), maximumLength);
            }
        };
        List<RuntimeRule> rules = List.of(
                runtimeRule(BuiltInSpanAttributeScrubbingRules.DIAGNOSTIC_TEXT.create(), RuleOrigin.BUILT_IN),
                runtimeRule(lengthLimit, RuleOrigin.EXTERNAL_EXTENSION));
        try (ScrubbingRuntime runtime = ScrubbingRuntime.create(rules, 1L, null, false);
             SpanProcessorHarness harness = SpanProcessorHarness.of(new ScrubbingSpanProcessor(runtime))) {
            runtime.bindAttributeValueLengthLimit(SpanLimits.getDefault().getMaxAttributeValueLength());
            Span span = harness.tracer().spanBuilder("url-with-length-limit").startSpan();
            span.setAttribute(key, "Failed https://service.example/?token=secret");

            // when
            span.end();

            // then
            assertThat(harness.spans().getFirst().getAttributes().get(key))
                    .isEqualTo("Failed https://service.example/?token=***");
            assertThat(runtime.truncatedActions()).isEqualTo(1L);
            assertThat(runtime.maskedActions()).isZero();
            assertThat(runtime.failures()).isZero();
        }
    }

    @Test
    void urlRuleDropsUnexpectedMessageArraysAndCountsTheAction() {
        // given
        AttributeKey<List<String>> key = AttributeKey.stringArrayKey("exception.message");
        try (ScrubbingRuntime runtime = ScrubbingRuntimeTestFixtures.fromBuiltInRules(
                List.of(BuiltInSpanAttributeScrubbingRules.DIAGNOSTIC_TEXT.create()));
             SpanProcessorHarness harness = SpanProcessorHarness.of(new ScrubbingSpanProcessor(runtime))) {
            Span span = harness.tracer().spanBuilder("unexpected-message-array").startSpan();
            span.setAttribute(key, Collections.nCopies(33, "https://user:secret@service.example/?token=secret"));

            // when
            span.end();

            // then
            assertThat(harness.spans().getFirst().getAttributes().get(key)).isNull();
            assertThat(harness.spans().getFirst().getAttributes().get(AttributeKey.stringKey("exception.message")))
                    .isEmpty();
            assertThat(runtime.droppedActions()).isEqualTo(1L);
            assertThat(runtime.maskedActions()).isZero();
            assertThat(runtime.failures()).isZero();
        }
    }

    @Test
    void urlMaskingUsesExistingActionCounters() {
        // given
        try (ScrubbingRuntime runtime = ScrubbingRuntimeTestFixtures.fromBuiltInRules(
                List.of(BuiltInSpanAttributeScrubbingRules.DIAGNOSTIC_TEXT.create()));
             SpanProcessorHarness harness = SpanProcessorHarness.of(new ScrubbingSpanProcessor(runtime))) {
            Span masked = harness.tracer().spanBuilder("masked").startSpan();
            masked.setAttribute("exception.message", "Failed https://service.example/?token=secret");

            // when
            runtime.scrubSpanAttributes((ReadWriteSpan) masked);
            masked.end();

            // then
            assertThat(harness.spans().getFirst().getAttributes().get(AttributeKey.stringKey("exception.message")))
                    .isEqualTo("Failed https://service.example/?token=***");
            assertThat(runtime.maskedActions()).isEqualTo(1L);
            assertThat(runtime.droppedActions()).isZero();
            assertThat(runtime.failures()).isZero();

            // when
            Span oversized = harness.tracer().spanBuilder("oversized").startSpan();
            oversized.setAttribute("exception.message", "s".repeat(4097));
            oversized.end();

            // then
            assertThat(runtime.maskedActions()).isEqualTo(1L);
            assertThat(runtime.droppedActions()).isEqualTo(1L);
            assertThat(harness.spans().getLast().getAttributes().get(AttributeKey.stringKey("exception.message")))
                    .isEmpty();
        }
    }

    @Test
    void urlHelperFailureDropsOnlyMessageAndCountsFailureOnce() {
        // given
        String input = "Failed https://service.example/?token=secret";
        try (ScrubbingRuntime runtime = ScrubbingRuntimeTestFixtures.fromBuiltInRules(
                List.of(BuiltInSpanAttributeScrubbingRules.DIAGNOSTIC_TEXT.create()));
             SpanProcessorHarness harness = SpanProcessorHarness.of(new ScrubbingSpanProcessor(runtime))) {
            Span span = harness.tracer().spanBuilder("url-helper-failure").startSpan();
            span.setAttribute("exception.message", input);
            span.setAttribute("detail", "safe detail");

            // when
            try (MockedStatic<DiagnosticTextSanitizer> masker = mockStatic(DiagnosticTextSanitizer.class)) {
                masker.when(() -> DiagnosticTextSanitizer.mask(input))
                        .thenThrow(new IllegalArgumentException("Simulated parser failure"));
                runtime.scrubSpanAttributes((ReadWriteSpan) span);
                masker.verify(() -> DiagnosticTextSanitizer.mask(input));
            }
            span.end();

            // then
            assertThat(harness.spans().getFirst().getAttributes().get(AttributeKey.stringKey("exception.message")))
                    .isEmpty();
            assertThat(harness.spans().getFirst().getAttributes().get(AttributeKey.stringKey("detail")))
                    .isEqualTo("safe detail");
            assertThat(runtime.failures()).isEqualTo(1L);
            assertThat(runtime.droppedActions()).isEqualTo(1L);
            assertThat(runtime.maskedActions()).isZero();
            assertThat(runtime.attributesVerified()).isTrue();
        }
    }

    private static final class MatchEverythingRule implements SpanAttributeScrubbingRule {
        @Nonnull
        @Override
        public String name() {
            return "match-everything-external";
        }

        @Nonnull
        @Override
        public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
            return ScrubbingDecision.mask(name());
        }
    }

    private static RuntimeRule runtimeRule(SpanAttributeScrubbingRule rule, RuleOrigin origin) {
        return new RuntimeRule(new ExecutableRule(rule, new RuleCircuitBreaker(rule.name())), origin);
    }

    @Test
    @DisplayName("SPI-projection: builtInTextRules() excludes BUNDLED_SPI and EXTERNAL_EXTENSION rules")
    void builtInTextRulesExcludeNonBuiltInOrigins() {
        // given
        SpanAttributeScrubbingRule builtIn = new MatchEverythingRule();
        SpanAttributeScrubbingRule external = new MatchEverythingRule();
        try (ScrubbingRuntime runtime = ScrubbingRuntime.create(
                List.of(runtimeRule(builtIn, RuleOrigin.BUILT_IN), runtimeRule(external, RuleOrigin.EXTERNAL_EXTENSION)),
                1L, null, false)) {

            // then
            assertThat(runtime.attributeExecutableRules()).hasSize(2);
            assertThat(runtime.builtInTextRules()).hasSize(1);
        }
    }

    @Test
    @DisplayName("Attribute plane sees BUILT_IN + BUNDLED_SPI + EXTERNAL_EXTENSION rules")
    void attributeExecutableRulesIncludeEveryOrigin() {
        // given
        try (ScrubbingRuntime runtime = ScrubbingRuntime.create(
                List.of(
                        runtimeRule(new MatchEverythingRule(), RuleOrigin.BUILT_IN),
                        runtimeRule(new MatchEverythingRule(), RuleOrigin.BUNDLED_SPI),
                        runtimeRule(new MatchEverythingRule(), RuleOrigin.EXTERNAL_EXTENSION)),
                1L,
                null,
                false
        )) {

            // then
            assertThat(runtime.attributeExecutableRules()).hasSize(3);
        }
    }

    @Test
    void classloader_close_failure_is_replayed_to_lifecycle_owner() {
        // given
        IOException closeFailure = new IOException("Simulated classloader close failure");
        URLClassLoader classLoader = new URLClassLoader(new URL[0]) {
            @Override
            public void close() throws IOException {
                throw closeFailure;
            }
        };
        ScrubbingRuntime runtime = ScrubbingRuntime.create(
                List.of(runtimeRule(new MatchEverythingRule(), RuleOrigin.BUILT_IN)),
                1L,
                null,
                false,
                classLoader
        );

        // when
        // then
        assertThatThrownBy(runtime::close)
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Failed to close scrubbing extension classloader")
                .hasCause(closeFailure);
        assertThatThrownBy(runtime::close)
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Failed to close scrubbing extension classloader")
                .hasCause(closeFailure);
    }

    @Test
    @DisplayName("Installation barrier: requireComplete() throws until every acknowledgement is present")
    void installationBarrierRequiresAllAcknowledgementsAndActualLimit() {
        // given
        try (ScrubbingRuntime runtime = ScrubbingRuntime.create(
                List.of(runtimeRule(new MatchEverythingRule(), RuleOrigin.BUILT_IN)),
                1L,
                null,
                false
        )) {

            // then
            assertThatThrownBy(runtime::requireInstallationComplete)
                    .isInstanceOf(IllegalStateException.class);

            // when
            runtime.acknowledgeProcessorInstalled();

            // then
            assertThatThrownBy(runtime::requireInstallationComplete).isInstanceOf(IllegalStateException.class);

            // when
            runtime.acknowledgeExportModeResolved();

            // then
            assertThatThrownBy(runtime::requireInstallationComplete).isInstanceOf(IllegalStateException.class);

            // when
            runtime.acknowledgeExporterWrapped();

            // then
            assertThatThrownBy(runtime::requireInstallationComplete).isInstanceOf(IllegalStateException.class);

            // when
            runtime.acknowledgeExportPathProtected();

            // then
            assertThatThrownBy(runtime::requireInstallationComplete)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Actual SDK attribute value length limit is not bound");

            // when
            runtime.bindAttributeValueLengthLimit(1000);

            // then
            runtime.requireInstallationComplete();
        }
    }

    @Test
    @DisplayName("Disabled runtime is inert: no rules, no HMAC, boundary calls are no-ops")
    void disabledRuntimeIsInert() {
        // given
        try (ScrubbingRuntime runtime = ScrubbingRuntime.disabled()) {

            // then
            assertThat(runtime.enabled()).isFalse();
            assertThat(runtime.attributeExecutableRules()).isEmpty();
            assertThat(runtime.builtInTextRules()).isEmpty();
            assertThat(runtime.hasHmacKey()).isFalse();
        }
    }

    @Test
    void boundary_count_recording_is_monotonic() {
        // given
        try (ScrubbingRuntime runtime = ScrubbingRuntime.disabled()) {

            // when
            runtime.recordBoundaryCriticalFailures(0L);
            runtime.recordBoundaryCriticalFailures(3L);

            // then
            assertThat(runtime.boundaryMetrics().criticalFailures()).isEqualTo(3L);
            assertThatThrownBy(() -> runtime.recordBoundaryCriticalFailures(-1L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(runtime.boundaryMetrics().criticalFailures()).isEqualTo(3L);
        }
    }

    @Test
    @DisplayName("attributesVerified is monotonic: cannot be set back to true once cleared")
    void attributesVerifiedIsMonotonicallyOneWay() {
        // given
        try (ScrubbingRuntime runtime = ScrubbingRuntime.create(List.of(), 1L, null, false)) {

            // then
            assertThat(runtime.attributesVerified()).isTrue();

            // when
            runtime.markAttributesUnverified();

            // then
            assertThat(runtime.attributesVerified()).isFalse();

            // when
            runtime.markAttributesUnverified();

            // then
            assertThat(runtime.attributesVerified()).as("still false, and only counted once as an activation event").isFalse();
        }
    }

    @Test
    @DisplayName("SKIPPED_CIRCUIT_OPEN does not increment failures; a new non-critical exception does")
    void skippedCircuitOpenDoesNotIncrementFailures() {
        // given
        SpanAttributeScrubbingRule throwing = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return "flaky-non-critical";
            }

            @Override
            public int priority() {
                return 900;
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
                throw new IllegalStateException("simulated non-critical rule failure");
            }
        };
        try (ScrubbingRuntime runtime = ScrubbingRuntime.create(
                List.of(new RuntimeRule(
                        new ExecutableRule(throwing, new RuleCircuitBreaker(throwing.name(), 1, 60_000L, 0L)),
                        RuleOrigin.BUILT_IN)),
                1L, null, false)) {

            // when
            try (SpanProcessorHarness harness = SpanProcessorHarness.of(new ScrubbingSpanProcessor(runtime))) {
                Tracer tracer = harness.tracer("t");
                Span first = tracer.spanBuilder("op-1").startSpan();
                first.setAttribute("any.key", "value");
                first.end();

                // then
                assertThat(runtime.failures()).isEqualTo(1L);
                assertThat(runtime.totalBreakerFailures()).isEqualTo(1L);

                // when
                Span second = tracer.spanBuilder("op-2").startSpan();
                second.setAttribute("any.key", "value");
                second.end();

                // then
                assertThat(runtime.failures())
                        .as("open breaker skip must not keep incrementing the failure counter")
                        .isEqualTo(1L);
                assertThat(runtime.totalBreakerFailures()).isEqualTo(1L);
                assertThat(runtime.skippedCircuitOpen()).isEqualTo(1L);
            }
        }
    }
}
