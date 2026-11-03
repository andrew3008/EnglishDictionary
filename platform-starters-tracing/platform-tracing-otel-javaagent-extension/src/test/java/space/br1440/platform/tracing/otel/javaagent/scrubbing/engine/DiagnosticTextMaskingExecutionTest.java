package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.runtime.scrubbing.DiagnosticTextSanitizer;

class DiagnosticTextMaskingExecutionTest {

    @Test
    void subsequentRulesObserveOnlyMaskedText() {
        // given
        String input = "Failed https://user:password@service.example/?token=secret";
        AtomicReference<Object> observed = new AtomicReference<>();
        SpanAttributeScrubbingRule observer = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return "observer";
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
                observed.set(value);
                return ScrubbingDecision.truncate(name(), 4);
            }
        };

        // when
        MergeOutcome outcome = MergeEngine.evaluateDetailed(
                List.of(executable(BuiltInSpanAttributeScrubbingRules.DIAGNOSTIC_TEXT.create()), executable(observer)),
                "exception.message", input);

        // then
        assertThat(observed.get()).isEqualTo("Failed https://***@service.example/?token=***");
        assertThat(outcome.maskedValue()).isEqualTo(observed.get());
        assertThat(outcome.decision().action()).isEqualTo(ScrubbingAction.TRUNCATE);
        assertThat(outcome.decision().terminal()).isFalse();
    }

    @Test
    void helperFailureAndOpenBreakerDropOnlySupportedText() {
        // given
        String input = "https://service.example/?token=secret";
        SpanAttributeScrubbingRule rule = BuiltInSpanAttributeScrubbingRules.DIAGNOSTIC_TEXT.create();
        RuleCircuitBreaker breaker = new RuleCircuitBreaker(rule.name(), 1, 60_000L, 60_000L);
        ExecutableRule executable = new ExecutableRule(rule, breaker);
        try (MockedStatic<DiagnosticTextSanitizer> masker = mockStatic(DiagnosticTextSanitizer.class)) {
            masker.when(() -> DiagnosticTextSanitizer.mask(input))
                    .thenThrow(new IllegalArgumentException("Simulated parser failure"));

            // when
            MergeOutcome failed = MergeEngine.evaluateDetailed(List.of(executable), "exception.message", input);
            MergeOutcome blocked = MergeEngine.evaluateDetailed(List.of(executable), "status.description", input);
            MergeOutcome excluded = MergeEngine.evaluateDetailed(List.of(executable), "url.full", input);

            // then
            assertThat(breaker.isOpen()).isTrue();
            assertThat(breaker.getTotalFailures()).isEqualTo(1L);
            for (MergeOutcome outcome : List.of(failed, blocked)) {
                assertThat(outcome.criticalFailures()).isEqualTo(1L);
                assertThat(outcome.requiresBoundaryCollapse()).isFalse();
                assertThat(outcome.decision().reason()).isEqualTo(ScrubbingDecision.SCRUBBING_FAILED_REASON);
                assertThat(outcome.decision().action()).isEqualTo(ScrubbingAction.DROP);
                assertThat(outcome.decision().terminal()).isTrue();
                assertThat(outcome.maskedValue()).isNull();
            }
            assertThat(excluded.decision().action()).isEqualTo(ScrubbingAction.KEEP);
            assertThat(excluded.criticalFailures()).isZero();
            masker.verify(() -> DiagnosticTextSanitizer.mask(input));
        }
    }

    @Test
    void criticalDropDoesNotInvokeFollowingCustomRule() {
        // given
        AtomicInteger calls = new AtomicInteger();
        SpanAttributeScrubbingRule custom = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return "custom-keep";
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
                calls.incrementAndGet();
                return ScrubbingDecision.keep();
            }
        };

        // when
        MergeOutcome outcome = MergeEngine.evaluateDetailed(
                List.of(executable(BuiltInSpanAttributeScrubbingRules.DIAGNOSTIC_TEXT.create()), executable(custom)),
                "exception.message", "https://user:secret");

        // then
        assertThat(outcome.decision().action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(outcome.decision().terminal()).isTrue();
        assertThat(calls).hasValue(0);
    }

    @Test
    void fatalHelperFailurePropagatesWithoutOpeningBreaker() {
        // given
        String input = "https://service.example/?token=secret";
        LinkageError failure = new LinkageError("Simulated fatal helper failure");
        ExecutableRule executable = executable(BuiltInSpanAttributeScrubbingRules.DIAGNOSTIC_TEXT.create());
        try (MockedStatic<DiagnosticTextSanitizer> masker = mockStatic(DiagnosticTextSanitizer.class)) {
            masker.when(() -> DiagnosticTextSanitizer.mask(input)).thenThrow(failure);

            // when
            // then
            assertThatThrownBy(() -> MergeEngine.evaluateDetailed(List.of(executable), "exception.message", input))
                    .isSameAs(failure);
            assertThat(executable.getBreaker().getTotalFailures()).isZero();
            assertThat(executable.getBreaker().isOpen()).isFalse();
        }
    }

    private static ExecutableRule executable(SpanAttributeScrubbingRule rule) {
        return new ExecutableRule(rule, new RuleCircuitBreaker(rule.name()));
    }
}
