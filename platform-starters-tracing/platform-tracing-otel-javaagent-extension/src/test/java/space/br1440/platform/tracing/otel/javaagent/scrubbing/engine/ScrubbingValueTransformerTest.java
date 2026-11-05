package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ScrubbingValueTransformer.Fallback;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ScrubbingValueTransformer.Result;

import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Value;

class ScrubbingValueTransformerTest {

    @Test
    void keepDoesNotInspectInputOrInvokeHash() {
        // given
        Object input = inaccessibleList(-1);

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.STRING_ARRAY, input,
                ScrubbingDecision.keep(), forbiddenHash());

        // then
        assertThat(result.action()).isEqualTo(ScrubbingAction.KEEP);
        assertThat(result.value()).isNull();
        assertThat(result.fallback()).isEqualTo(Fallback.NONE);
    }

    @Test
    void dropDoesNotMaterializeOrInspectInput() {
        // given
        Object input = inaccessibleList(-1);

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.STRING_ARRAY, input,
                ScrubbingDecision.drop("password"), forbiddenHash());

        // then
        assertThat(result.action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(result.value()).isNull();
    }

    @Test
    void hashReceivesOriginalStringOnce() {
        // given
        AtomicInteger calls = new AtomicInteger();
        UnaryOperator<String> hash = input -> {
            assertThat(input).isEqualTo("sensitive@example.com");
            calls.incrementAndGet();
            return "digest";
        };

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.STRING, "sensitive@example.com",
                ScrubbingDecision.hash("email"), hash);

        // then
        assertThat(result.action()).isEqualTo(ScrubbingAction.HASH);
        assertThat(result.value()).isEqualTo("digest");
        assertThat(result.fallback()).isEqualTo(Fallback.NONE);
        assertThat(calls).hasValue(1);
    }

    @Test
    void missingKeyMasksAndReportsFallback() {
        // given
        String input = "sensitive@example.com";

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.STRING, input,
                ScrubbingDecision.hash("email"), null);

        // then
        assertThat(result.action()).isEqualTo(ScrubbingAction.MASK);
        assertThat(result.value()).isEqualTo("***");
        assertThat(result.fallback()).isEqualTo(Fallback.MISSING_HMAC_KEY);
    }

    @ParameterizedTest
    @EnumSource(value = ScrubbingAction.class, names = {"MASK", "HASH", "TRUNCATE"})
    void nullStringDoesNotBecomeText(ScrubbingAction action) {
        // given
        ScrubbingDecision decision = new ScrubbingDecision(action, "test", 3, false);

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.STRING, null, decision, forbiddenHash());

        // then
        assertThat(result.action()).isEqualTo(ScrubbingAction.KEEP);
        assertThat(result.value()).isNull();
    }

    @Test
    void noOpTruncationAndAlreadyMaskedValueNeedNoWrite() {
        // given
        String input = "safe";

        // when
        Result truncate = ScrubbingValueTransformer.transform(AttributeType.STRING, input,
                ScrubbingDecision.truncate("test", 10), null);
        Result mask = ScrubbingValueTransformer.transform(AttributeType.STRING, "***",
                ScrubbingDecision.mask("test"), null);

        // then
        assertThat(truncate.action()).isEqualTo(ScrubbingAction.KEEP);
        assertThat(mask.action()).isEqualTo(ScrubbingAction.KEEP);
    }

    @Test
    void truncateDoesNotSplitSurrogatePair() {
        // given
        String input = "A\uD83D\uDE80B";

        // when
        Result split = ScrubbingValueTransformer.transform(AttributeType.STRING, input,
                ScrubbingDecision.truncate("test", 2), null);
        Result complete = ScrubbingValueTransformer.transform(AttributeType.STRING, input,
                ScrubbingDecision.truncate("test", 3), null);

        // then
        assertThat(split.value()).isEqualTo("A");
        assertThat(complete.value()).isEqualTo("A\uD83D\uDE80");
    }

    @Test
    void invalidIpMasksInsteadOfTruncatingRawText() {
        // given
        ScrubbingDecision decision = ScrubbingDecision.truncate(ScrubbingValueTransformer.IP_ADDRESS_REASON, 3);

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.STRING, "secret.invalid", decision, null);

        // then
        assertThat(result.action()).isEqualTo(ScrubbingAction.MASK);
        assertThat(result.value()).isEqualTo("***");
        assertThat(result.fallback()).isEqualTo(Fallback.INVALID_IP);
    }

    @Test
    void ipRuleUsesPrefixInsteadOfMaxLength() {
        // given
        ScrubbingDecision decision = ScrubbingDecision.truncate(ScrubbingValueTransformer.IP_ADDRESS_REASON, 1);

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.STRING, "192.168.12.34", decision, null);

        // then
        assertThat(result.value()).isEqualTo("192.168.12.0");
        assertThat(result.action()).isEqualTo(ScrubbingAction.TRUNCATE);
    }

    @Test
    void scalarBudgetIsAppliedBeforeKeyBasedHash() {
        // given
        String input = "x".repeat(ScrubbingValueTransformer.MAX_INPUT_CHARS + 1);

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.STRING, input,
                ScrubbingDecision.hash("customer-id"), forbiddenHash());

        // then
        assertThat(result.value()).isEqualTo("***");
        assertThat(result.fallback()).isEqualTo(Fallback.INPUT_LIMIT);
    }

    @Test
    void exactScalarBudgetAllowsHash() {
        // given
        String input = "x".repeat(ScrubbingValueTransformer.MAX_INPUT_CHARS);

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.STRING, input,
                ScrubbingDecision.hash("customer-id"), text -> "digest");

        // then
        assertThat(result.value()).isEqualTo("digest");
        assertThat(result.fallback()).isEqualTo(Fallback.NONE);
    }

    @Test
    void stringArrayPreservesNullAndHashesEveryNonNullElement() {
        // given
        List<String> input = Arrays.asList("first", null, "second");
        AtomicInteger calls = new AtomicInteger();

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.STRING_ARRAY, input,
                ScrubbingDecision.hash("key"), text -> {
                    calls.incrementAndGet();
                    return "digest-" + text;
                });

        // then
        assertThat(result.value()).isEqualTo(Arrays.asList("digest-first", null, "digest-second"));
        assertThat(input).containsExactly("first", null, "second");
        assertThat(calls).hasValue(2);
        assertThat(result.action()).isEqualTo(ScrubbingAction.HASH);
    }

    @Test
    void arrayBudgetIsCheckedBeforeHashingFirstElement() {
        // given
        List<String> input = List.of("early-match", "x".repeat(ScrubbingValueTransformer.MAX_INPUT_CHARS));

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.STRING_ARRAY, input,
                ScrubbingDecision.hash("key"), forbiddenHash());

        // then
        assertThat(result.value()).isEqualTo(List.of("***"));
        assertThat(result.fallback()).isEqualTo(Fallback.INPUT_LIMIT);
    }

    @Test
    void oversizedArrayDoesNotReadItsElements() {
        // given
        List<Object> input = inaccessibleList(ScrubbingValueTransformer.MAX_ARRAY_ELEMENTS + 1);

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.STRING_ARRAY, input,
                ScrubbingDecision.hash("key"), forbiddenHash());

        // then
        assertThat(result.value()).isEqualTo(List.of("***"));
        assertThat(result.fallback()).isEqualTo(Fallback.INPUT_LIMIT);
    }

    @Test
    void explicitInputLimitDecisionDoesNotReadEvenArraySize() {
        // given
        List<Object> input = inaccessibleList(-1);

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.STRING_ARRAY, input,
                ScrubbingDecision.mask(ScrubbingValueTransformer.INPUT_LIMIT_REASON), forbiddenHash());

        // then
        assertThat(result.value()).isEqualTo(List.of("***"));
        assertThat(result.fallback()).isEqualTo(Fallback.INPUT_LIMIT);
    }

    @Test
    void malformedStringArrayCollapsesBeforeAnyHash() {
        // given
        List<Object> input = List.of("first", 123L);

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.STRING_ARRAY, input,
                ScrubbingDecision.hash("key"), forbiddenHash());

        // then
        assertThat(result.value()).isEqualTo(List.of("***"));
        assertThat(result.fallback()).isEqualTo(Fallback.MALFORMED_INPUT);
    }

    @Test
    void exactArrayAndAggregateInputBudgetsAllowEveryHash() {
        // given
        List<String> input = Collections.nCopies(ScrubbingValueTransformer.MAX_ARRAY_ELEMENTS, "x".repeat(128));
        AtomicInteger calls = new AtomicInteger();

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.STRING_ARRAY, input,
                ScrubbingDecision.hash("key"), value -> {
                    calls.incrementAndGet();
                    return "digest";
                });

        // then
        assertThat(result.action()).isEqualTo(ScrubbingAction.HASH);
        assertThat((List<?>) result.value()).hasSize(ScrubbingValueTransformer.MAX_ARRAY_ELEMENTS);
        assertThat(calls).hasValue(ScrubbingValueTransformer.MAX_ARRAY_ELEMENTS);
    }

    @ParameterizedTest
    @EnumSource(value = AttributeType.class, names = {"STRING_ARRAY", "LONG_ARRAY", "DOUBLE_ARRAY", "BOOLEAN_ARRAY"})
    void emptyArraysNeedNoWrite(AttributeType type) {
        // given
        List<Object> input = List.of();

        // when
        Result result = ScrubbingValueTransformer.transform(type, input, ScrubbingDecision.mask("key"), null);

        // then
        assertThat(result.action()).isEqualTo(ScrubbingAction.KEEP);
        assertThat(ScrubbingValueTransformer.dropValue(type, input)).isEqualTo(List.of());
    }

    @ParameterizedTest
    @EnumSource(value = AttributeType.class, names = {"STRING", "LONG", "DOUBLE", "BOOLEAN"})
    void malformedScalarDoesNotEscapeAsRawInput(AttributeType type) {
        // given
        Object input = new Object();

        // when
        Result result = ScrubbingValueTransformer.transform(type, input, ScrubbingDecision.hash("key"), forbiddenHash());

        // then
        assertThat(result.action()).isEqualTo(ScrubbingAction.MASK);
        assertThat(result.fallback()).isEqualTo(Fallback.MALFORMED_INPUT);
        assertThat(result.value()).isNotSameAs(input);
    }

    @Test
    void aggregateOutputBudgetIsBounded() {
        // given
        List<String> input = List.of("first", "second");

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.STRING_ARRAY, input,
                ScrubbingDecision.hash("key"), text -> "x".repeat(3000));

        // then
        assertThat(result.value()).isEqualTo(List.of("***"));
        assertThat(result.fallback()).isEqualTo(Fallback.INPUT_LIMIT);
    }

    @ParameterizedTest
    @MethodSource("numericValues")
    void numericHashUsesTypedMask(AttributeType type, Object input, Object expected) {
        // given
        ScrubbingDecision decision = ScrubbingDecision.hash("key");

        // when
        Result result = ScrubbingValueTransformer.transform(type, input, decision, forbiddenHash());

        // then
        assertThat(result.value()).isEqualTo(expected);
        assertThat(result.action()).isEqualTo(ScrubbingAction.MASK);
        assertThat(result.fallback()).isEqualTo(Fallback.NONE);
    }

    @ParameterizedTest
    @MethodSource("numericValues")
    void numericTruncateKeepsValue(AttributeType type, Object input) {
        // given
        ScrubbingDecision decision = ScrubbingDecision.truncate("key", 0);

        // when
        Result result = ScrubbingValueTransformer.transform(type, input, decision, forbiddenHash());

        // then
        assertThat(result.action()).isEqualTo(ScrubbingAction.KEEP);
    }

    @Test
    void alreadySuppressedNumericArrayNeedsNoWrite() {
        // given
        List<Long> input = List.of(0L, 0L);

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.LONG_ARRAY, input,
                ScrubbingDecision.mask("key"), null);

        // then
        assertThat(result.action()).isEqualTo(ScrubbingAction.KEEP);
    }

    @Test
    void oversizedNumericArrayHasBoundedReplacement() {
        // given
        List<Object> input = inaccessibleList(Integer.MAX_VALUE);

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.LONG_ARRAY, input,
                ScrubbingDecision.mask("key"), null);

        // then
        assertThat(result.value()).isEqualTo(List.of(0L));
        assertThat(result.fallback()).isEqualTo(Fallback.INPUT_LIMIT);
    }

    @Test
    void malformedNumericArrayHasTypedReplacement() {
        // given
        List<Object> input = List.of(4L, "raw-secret");

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.LONG_ARRAY, input,
                ScrubbingDecision.mask("key"), null);

        // then
        assertThat(result.value()).isEqualTo(List.of(0L));
        assertThat(result.fallback()).isEqualTo(Fallback.MALFORMED_INPUT);
    }

    @ParameterizedTest
    @MethodSource("failClosedValues")
    void failClosedUsesOneTypedSentinelWithoutInspectingInput(AttributeType type, Object expected) {
        // given
        Object input = inaccessibleList(-1);

        // when
        Result result = ScrubbingValueTransformer.transform(type, input, ScrubbingDecision.scrubbingFailed(), forbiddenHash());

        // then
        assertThat(result.value()).isEqualTo(expected);
        assertThat(result.action()).isEqualTo(ScrubbingAction.MASK);
        assertThat(ScrubbingValueTransformer.failClosedValue(type)).isEqualTo(expected);
    }

    @Test
    void dropValueDoesNotReadElementsAndBoundsSize() {
        // given
        List<Object> withinBudget = inaccessibleList(3);
        List<Object> oversized = inaccessibleList(Integer.MAX_VALUE);

        // when
        Object regular = ScrubbingValueTransformer.dropValue(AttributeType.STRING_ARRAY, withinBudget);
        Object bounded = ScrubbingValueTransformer.dropValue(AttributeType.LONG_ARRAY, oversized);

        // then
        assertThat(regular).isEqualTo(List.of("", "", ""));
        assertThat(bounded).isEqualTo(List.of(0L));
    }

    @Test
    void maskedTextSurvivesNoOpFinalDecision() {
        // given
        MergeOutcome outcome = new MergeOutcome(ScrubbingDecision.truncate("test", 100), "safe ***", 0L, 0L, 0L, false);

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.STRING, "raw secret", outcome, null);

        // then
        assertThat(result.action()).isEqualTo(ScrubbingAction.MASK);
        assertThat(result.value()).isEqualTo("safe ***");
    }

    @Test
    void maskedTextIsUsedInsteadOfRawTextForHash() {
        // given
        MergeOutcome outcome = new MergeOutcome(ScrubbingDecision.hash("test"), "safe ***", 0L, 0L, 0L, false);

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.STRING, "raw secret", outcome, input -> {
            assertThat(input).isEqualTo("safe ***");
            return "digest";
        });

        // then
        assertThat(result.value()).isEqualTo("digest");
    }

    @Test
    void maskedTextWithNonStringTypeUsesTypedFallback() {
        // given
        MergeOutcome keep = new MergeOutcome(ScrubbingDecision.keep(), "safe ***", 0L, 0L, 0L, false);
        MergeOutcome truncate = new MergeOutcome(ScrubbingDecision.truncate("test", 100), "safe ***", 0L, 0L, 0L, false);

        // when
        Result kept = ScrubbingValueTransformer.transform(AttributeType.LONG, "raw secret", keep, forbiddenHash());
        Result truncated = ScrubbingValueTransformer.transform(AttributeType.LONG, "raw secret", truncate, forbiddenHash());

        // then
        assertThat(kept.action()).isEqualTo(ScrubbingAction.MASK);
        assertThat(kept.value()).isEqualTo(0L);
        assertThat(kept.fallback()).isEqualTo(Fallback.MALFORMED_INPUT);
        assertThat(truncated).isEqualTo(kept);
    }

    @Test
    void dropTakesPrecedenceOverMalformedMaskedTextType() {
        // given
        MergeOutcome outcome = new MergeOutcome(ScrubbingDecision.drop("test"), "safe ***", 0L, 0L, 0L, false);

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.LONG, "raw secret", outcome, forbiddenHash());

        // then
        assertThat(result.action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(result.value()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = ScrubbingAction.class, names = {"KEEP", "TRUNCATE"})
    void oversizedMaskedTextCannotBypassTheOutputBudget(ScrubbingAction action) {
        // given
        String masked = "x".repeat(ScrubbingValueTransformer.MAX_INPUT_CHARS + 1);
        ScrubbingDecision decision = new ScrubbingDecision(action, "test", masked.length() + 1, false);
        MergeOutcome outcome = new MergeOutcome(decision, masked, 0L, 0L, 0L, false);

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.STRING, "raw secret", outcome, forbiddenHash());
        Result unchanged = ScrubbingValueTransformer.transform(AttributeType.STRING, masked, outcome, forbiddenHash());

        // then
        assertThat(result.action()).isEqualTo(ScrubbingAction.MASK);
        assertThat(result.value()).isEqualTo("***");
        assertThat(result.fallback()).isEqualTo(Fallback.INPUT_LIMIT);
        assertThat(unchanged).isEqualTo(result);
    }

    @Test
    void dropTakesPrecedenceOverOversizedMaskedText() {
        // given
        String masked = "x".repeat(ScrubbingValueTransformer.MAX_INPUT_CHARS + 1);
        MergeOutcome outcome = new MergeOutcome(ScrubbingDecision.drop("test"), masked, 0L, 0L, 0L, false);

        // when
        Result result = ScrubbingValueTransformer.transform(AttributeType.STRING, "raw secret", outcome, forbiddenHash());

        // then
        assertThat(result.action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(result.value()).isNull();
    }

    @Test
    void resultDescriptionDoesNotDiscloseReplacement() {
        // given
        Result result = new Result(ScrubbingAction.HASH, "sensitive-digest", Fallback.NONE);

        // when
        String description = result.toString();

        // then
        assertThat(description).contains("HASH", "NONE").doesNotContain("sensitive-digest");
    }

    @Test
    void hashFailurePropagatesToTheOwningAdapter() {
        // given
        IllegalStateException failure = new IllegalStateException("Digest failed");

        // when
        // then
        assertThatThrownBy(() -> ScrubbingValueTransformer.transform(AttributeType.STRING, "secret",
                ScrubbingDecision.hash("key"), value -> { throw failure; })).isSameAs(failure);
    }

    @Test
    void inaccessibleSizePropagatesToTheOwningAdapter() {
        // given
        List<Object> input = inaccessibleList(-1);

        // when
        // then
        assertThatThrownBy(() -> ScrubbingValueTransformer.transform(AttributeType.STRING_ARRAY, input,
                ScrubbingDecision.mask("key"), null)).isInstanceOf(IllegalStateException.class);
    }

    private static Stream<Arguments> numericValues() {
        return Stream.of(
                Arguments.of(AttributeType.LONG, 7L, 0L),
                Arguments.of(AttributeType.DOUBLE, 3.5d, 0.0d),
                Arguments.of(AttributeType.BOOLEAN, true, false),
                Arguments.of(AttributeType.LONG_ARRAY, List.of(7L, 8L), List.of(0L, 0L)),
                Arguments.of(AttributeType.DOUBLE_ARRAY, List.of(3.5d, 2.0d), List.of(0.0d, 0.0d)),
                Arguments.of(AttributeType.BOOLEAN_ARRAY, List.of(true, false), List.of(false, false)));
    }

    private static Stream<Arguments> failClosedValues() {
        String failed = ScrubbingDecision.SCRUBBING_FAILED_REASON;
        return Stream.of(
                Arguments.of(AttributeType.STRING, failed),
                Arguments.of(AttributeType.STRING_ARRAY, List.of(failed)),
                Arguments.of(AttributeType.LONG, 0L),
                Arguments.of(AttributeType.DOUBLE, 0.0d),
                Arguments.of(AttributeType.BOOLEAN, false),
                Arguments.of(AttributeType.LONG_ARRAY, List.of(0L)),
                Arguments.of(AttributeType.DOUBLE_ARRAY, List.of(0.0d)),
                Arguments.of(AttributeType.BOOLEAN_ARRAY, List.of(false)),
                Arguments.of(AttributeType.VALUE, Value.of(failed)));
    }

    private static UnaryOperator<String> forbiddenHash() {
        return value -> { throw new AssertionError("Hash must not be invoked"); };
    }

    private static List<Object> inaccessibleList(int size) {
        return new AbstractList<>() {
            @Override
            public Object get(int index) {
                throw new AssertionError("Array elements must not be read");
            }

            @Override
            public int size() {
                if (size < 0) {
                    throw new IllegalStateException("Array size unavailable");
                }

                return size;
            }
        };
    }
}
