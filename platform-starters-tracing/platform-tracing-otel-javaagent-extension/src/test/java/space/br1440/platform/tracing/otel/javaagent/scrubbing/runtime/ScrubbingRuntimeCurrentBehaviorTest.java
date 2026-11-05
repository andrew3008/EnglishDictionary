package space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOrigin;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuntimeRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

class ScrubbingRuntimeCurrentBehaviorTest {

    private static final String FAILED = ScrubbingDecision.SCRUBBING_FAILED_REASON;

    @Test
    void diagnosticDropReplacesInvalidArraysWithoutInspectingTheirContentsOrSize() {
        // given
        List<Object> inaccessible = new AbstractList<>() {
            @Override
            public Object get(int index) {
                throw new AssertionError("Rejected array elements must not be read");
            }

            @Override
            public int size() {
                throw new AssertionError("Rejected array size must not be read");
            }
        };

        List<AttributeKey<?>> keys = List.of(
                AttributeKey.stringArrayKey("exception.message"),
                AttributeKey.longArrayKey("exception.message"),
                AttributeKey.doubleArrayKey("exception.message"),
                AttributeKey.booleanArrayKey("exception.message"));
        SpanAttributeScrubbingRule rule = BuiltInSpanAttributeScrubbingRules.DIAGNOSTIC_TEXT.create();
        RuleCircuitBreaker breaker = new RuleCircuitBreaker(rule.name(), 1, 60_000L, 0L);
        try (ScrubbingRuntime runtime = ScrubbingRuntime.create(List.of(
                new RuntimeRule(new ExecutableRule(rule, breaker), RuleOrigin.BUILT_IN)), 1L, null, false)) {
            for (AttributeKey<?> key : keys) {
                // when
                Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(key, inaccessible));

                // then
                assertThat(written).containsOnlyKeys(AttributeKey.stringKey("exception.message"));
                assertThat(written.get(AttributeKey.stringKey("exception.message"))).isEqualTo("");
            }

            assertThat(runtime.droppedActions()).isEqualTo(4L);
            assertThat(runtime.maskedActions()).isZero();
            assertThat(runtime.failures()).isZero();
            assertThat(runtime.attributesVerified()).isTrue();
            assertThat(runtime.collapseModeActivations()).isZero();
        }
    }

    @Test
    @DisplayName("MASK/HASH числа и boolean учитываются как маскирование, DROP — как подавление атрибута")
    void numericAndBooleanActionsCountTheAppliedEffect() {
        // given
        AttributeKey<Long> longKey = AttributeKey.longKey("geo.lat");
        AttributeKey<Double> doubleKey = AttributeKey.doubleKey("geo.lon");
        AttributeKey<Boolean> booleanKey = AttributeKey.booleanKey("geo.flag");

        // when
        try (ScrubbingRuntime maskedRuntime = runtime(ScrubbingDecision.mask("current-behavior"));
             ScrubbingRuntime dropped = runtime(ScrubbingDecision.drop("current-behavior"));
             ScrubbingRuntime hashed = runtime(ScrubbingDecision.hash("current-behavior"), null)) {
            Map<AttributeKey<?>, Object> masked = scrub(maskedRuntime, attributes(longKey, 55L));
            Map<AttributeKey<?>, Object> droppedValues = scrub(dropped, attributes(doubleKey, 1.5d));
            Map<AttributeKey<?>, Object> hashedValues = scrub(hashed, attributes(booleanKey, true));

            // then
            assertThat(masked.get(longKey)).isEqualTo(0L);
            assertThat(maskedRuntime.droppedActions()).isZero();
            assertThat(maskedRuntime.maskedActions()).isEqualTo(1);
            assertThat(droppedValues.get(doubleKey)).isEqualTo(0.0d);
            assertThat(hashedValues.get(booleanKey)).isEqualTo(false);
            assertThat(dropped.droppedActions()).isEqualTo(1);
            assertThat(dropped.maskedActions()).isZero();
            assertThat(hashed.droppedActions()).isZero();
            assertThat(hashed.hashMissingKey()).isZero();
            assertThat(hashed.maskedActions()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Маскирование массива сохраняет допустимую длину и учитывает один атрибут")
    void numericArrayMaskKeepsLengthAndCountsOneAttribute() {
        // given
        AttributeKey<List<Long>> key = AttributeKey.longArrayKey("geo.parts");

        try (ScrubbingRuntime runtime = runtime(ScrubbingDecision.mask("current-behavior"))) {
            // when
            Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(key, List.of(7L, 8L, 9L)));

            // then
            assertThat(written.get(key)).isEqualTo(List.of(0L, 0L, 0L));
            assertThat(runtime.droppedActions()).isZero();
            assertThat(runtime.maskedActions()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("TRUNCATE для числа и boolean оставляет значение")
    void truncateOnNumericAndBooleanKeepsTheValue() {
        // given
        AttributeKey<Long> longKey = AttributeKey.longKey("client.address");
        AttributeKey<Boolean> booleanKey = AttributeKey.booleanKey("client.address.internal");

        try (ScrubbingRuntime runtime = runtime(ScrubbingDecision.truncate("ip-address", 3))) {
            // when
            Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(longKey, 167838211L, booleanKey, true));

            // then
            assertThat(written).isEmpty();
            assertThat(runtime.truncatedActions()).isZero();
            assertThat(runtime.droppedActions()).isZero();
            assertThat(runtime.maskedActions()).isZero();
        }
    }

    @Test
    @DisplayName("HASH строки без ключа возвращает маску и считает оба счётчика")
    void hashWithoutKeyMasksAndCountsBoth() {
        // given
        AttributeKey<String> key = AttributeKey.stringKey("user.email");

        try (ScrubbingRuntime runtime = runtime(ScrubbingDecision.hash("current-behavior"), null)) {
            // when
            Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(key, "ivan@example.com"));

            // then
            assertThat(written.get(key)).isEqualTo("***");
            assertThat(runtime.hashMissingKey()).isEqualTo(1);
            assertThat(runtime.maskedActions()).isEqualTo(1);
            assertThat(runtime.hashedActions()).isZero();
        }
    }

    @Test
    @DisplayName("TRUNCATE без изменения значения не записывает атрибут и не увеличивает счётчик")
    void unchangedTruncateDoesNotWriteOrCountAnAction() {
        // given
        AttributeKey<String> key = AttributeKey.stringKey("note");

        try (ScrubbingRuntime runtime = runtime(ScrubbingDecision.truncate("note", 100))) {
            // when
            Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(key, "short"));

            // then
            assertThat(written).isEmpty();
            assertThat(runtime.truncatedActions()).isZero();
        }
    }

    @Test
    @DisplayName("неудачное усечение IP возвращает маску и увеличивает failures")
    void failedIpTruncationMasksAndCountsFailure() {
        // given
        AttributeKey<String> key = AttributeKey.stringKey("client.address");

        try (ScrubbingRuntime runtime = runtime(ScrubbingDecision.truncate("ip-address", 64))) {
            // when
            Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(key, "not-an-ip"));

            // then
            assertThat(written.get(key)).isEqualTo("***");
            assertThat(runtime.truncatedActions()).isZero();
            assertThat(runtime.maskedActions()).isEqualTo(1);
            assertThat(runtime.failures()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("fail-closed схлопывает массив до одного sentinel")
    void failClosedCollapsesArrayToOneElement() {
        // given
        AttributeKey<List<Long>> key = AttributeKey.longArrayKey("secret.parts");

        try (ScrubbingRuntime runtime = runtime(ScrubbingDecision.scrubbingFailed())) {
            // when
            Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(key, List.of(7L, 8L, 9L)));

            // then
            assertThat(written.get(key)).isEqualTo(List.of(0L));
            assertThat(runtime.maskedActions()).isEqualTo(1);
            assertThat(runtime.attributesVerified()).isTrue();
        }
    }

    @Test
    @DisplayName("AttributeType.VALUE не изменяется")
    void valueAttributeIsLeftUntouched() {
        // given
        AttributeKey<Value<?>> key = AttributeKey.valueKey("payload");

        try (ScrubbingRuntime runtime = runtime(ScrubbingDecision.mask("current-behavior"))) {
            // when
            Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(key, Value.of("secret")));

            // then
            assertThat(written).doesNotContainKey(key);
            assertThat(key.getType()).isEqualTo(AttributeType.VALUE);
            assertThat(runtime.maskedActions()).isZero();
            assertThat(runtime.droppedActions()).isZero();
            assertThat(runtime.attributesVerified()).isTrue();
        }
    }

    @Test
    void liveLookupsPreserveValueOriginAfterSnapshotNormalization() {
        // given
        AttributeKey<Value<?>> valueKey = AttributeKey.valueKey("payload");
        AttributeKey<String> typedKey = AttributeKey.stringKey("payload");

        try (SdkTracerProvider provider = realSpanProvider()) {
            ReadWriteSpan valueOrigin = (ReadWriteSpan) provider.get("value-origin").spanBuilder("value-origin").startSpan();
            valueOrigin.setAttribute(valueKey, Value.of("secret"));
            ReadWriteSpan nativeOrigin = (ReadWriteSpan) provider.get("native-origin").spanBuilder("native-origin").startSpan();
            nativeOrigin.setAttribute(typedKey, "secret");

            // when
            Attributes valueSnapshot = valueOrigin.toSpanData().getAttributes();
            Attributes nativeSnapshot = nativeOrigin.toSpanData().getAttributes();

            // then
            assertThat(valueSnapshot.get(typedKey)).isEqualTo("secret");
            assertThat(valueOrigin.getAttributes().asMap()).isEqualTo(valueSnapshot.asMap());
            assertThat(valueOrigin.getAttribute(valueKey)).isEqualTo(Value.of("secret"));
            assertThat(valueOrigin.getAttribute(typedKey)).isNull();

            assertThat(nativeSnapshot.get(typedKey)).isEqualTo("secret");
            assertThat(nativeOrigin.getAttributes().asMap()).isEqualTo(nativeSnapshot.asMap());
            assertThat(nativeOrigin.getAttribute(valueKey)).isNull();
            assertThat(nativeOrigin.getAttribute(typedKey)).isEqualTo("secret");

            valueOrigin.end();
            nativeOrigin.end();
        }
    }

    @Test
    void normalizedValueOriginIsReservedForBoundaryWhileNativeOriginIsScrubbed() {
        // given
        AttributeKey<Value<?>> valueKey = AttributeKey.valueKey("payload");
        AttributeKey<String> typedKey = AttributeKey.stringKey("payload");

        try (ScrubbingRuntime runtime = runtime(ScrubbingDecision.mask("test"));
             SdkTracerProvider provider = realSpanProvider()) {
            ReadWriteSpan valueOrigin = (ReadWriteSpan) provider.get("value-origin").spanBuilder("value-origin").startSpan();
            valueOrigin.setAttribute(valueKey, Value.of("secret"));
            ReadWriteSpan nativeOrigin = (ReadWriteSpan) provider.get("native-origin").spanBuilder("native-origin").startSpan();
            nativeOrigin.setAttribute(typedKey, "secret");

            // when
            runtime.scrubSpanAttributes(valueOrigin);
            runtime.scrubSpanAttributes(nativeOrigin);

            // then
            assertThat(valueOrigin.getAttribute(valueKey)).isEqualTo(Value.of("secret"));
            assertThat(valueOrigin.getAttribute(typedKey)).isNull();
            assertThat(nativeOrigin.getAttribute(valueKey)).isNull();
            assertThat(nativeOrigin.getAttribute(typedKey)).isEqualTo("***");
            assertThat(runtime.maskedActions()).isEqualTo(1L);

            valueOrigin.end();
            nativeOrigin.end();
        }
    }

    @Test
    void normalizedValueOriginRemainsBoundaryOwnedDuringFailClosedSnapshot() {
        // given
        AttributeKey<Value<?>> valueKey = AttributeKey.valueKey("payload");
        AttributeKey<String> projectedValueKey = AttributeKey.stringKey("payload");
        AttributeKey<String> nativeKey = AttributeKey.stringKey("neighbor.secret");

        try (ScrubbingRuntime runtime = runtime(ScrubbingDecision.mask("test"));
             SdkTracerProvider provider = realSpanProvider()) {
            ReadWriteSpan span = (ReadWriteSpan) provider.get("fail-closed").spanBuilder("fail-closed").startSpan();
            span.setAttribute(valueKey, Value.of("secret"));
            span.setAttribute(nativeKey, "native-secret");
            ReadWriteSpan failingSpan = failFirstMutation(span);

            // when
            runtime.scrubSpanAttributes(failingSpan);

            // then
            assertThat(span.toSpanData().getAttributes().get(projectedValueKey)).isEqualTo("secret");
            assertThat(span.getAttribute(valueKey)).isEqualTo(Value.of("secret"));
            assertThat(span.getAttribute(projectedValueKey)).isNull();
            assertThat(span.getAttribute(nativeKey)).isEqualTo(FAILED);
            assertThat(runtime.failures()).isEqualTo(1L);
            assertThat(runtime.attributesVerified()).isFalse();
            assertThat(runtime.collapseModeActivations()).isEqualTo(1L);
            assertThat(runtime.maskedActions()).isEqualTo(1L);

            span.end();
        }
    }

    @Test
    @DisplayName("null внутри STRING_ARRAY сохраняется на своей позиции")
    void stringArrayKeepsNullElement() {
        // given
        AttributeKey<List<String>> key = AttributeKey.stringArrayKey("tags");
        List<String> input = Arrays.asList("alpha", null, "beta");

        try (ScrubbingRuntime runtime = runtime(ScrubbingDecision.mask("current-behavior"))) {
            // when
            Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(key, input));

            // then
            assertThat(written.get(key)).isEqualTo(Arrays.asList("***", null, "***"));
            assertThat(runtime.maskedActions()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("DROP строки пишет пустую строку и оставляет атрибут")
    void dropStringWritesEmptyAndKeepsTheAttribute() {
        // given
        AttributeKey<String> key = AttributeKey.stringKey("user.email");

        try (ScrubbingRuntime runtime = runtime(ScrubbingDecision.drop("current-behavior"))) {
            // when
            Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(key, "ivan@example.com"));

            // then
            assertThat(written).containsKey(key);
            assertThat(written.get(key)).isEqualTo("");
            assertThat(runtime.droppedActions()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("исключение переписывает весь исходный снимок")
    void mutationFailureRewritesTheWholeSnapshot() {
        // given
        AttributeKey<String> first = AttributeKey.stringKey("first.secret");
        AttributeKey<String> second = AttributeKey.stringKey("second.secret");

        try (ScrubbingRuntime runtime = runtime(ScrubbingDecision.mask("current-behavior"))) {
            // when
            Map<AttributeKey<?>, Object> written = new LinkedHashMap<>();
            scrub(runtime, attributes(first, "first-raw", second, "second-raw"), false,
                    new IllegalStateException("synthetic mutation failure"), written);

            // then
            assertThat(written.get(first)).isEqualTo(FAILED);
            assertThat(written.get(second)).isEqualTo(FAILED);
            assertThat(runtime.failures()).isEqualTo(1);
            assertThat(runtime.attributesVerified()).isFalse();
            assertThat(runtime.collapseModeActivations()).isEqualTo(1);
            assertThat(runtime.maskedActions()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("недоступный снимок не трогает атрибуты и включает collapse")
    void unavailableSnapshotLeavesAttributesAndCollapses() {
        // given
        AttributeKey<String> key = AttributeKey.stringKey("user.email");

        try (ScrubbingRuntime runtime = runtime(ScrubbingDecision.mask("current-behavior"))) {
            // when
            Map<AttributeKey<?>, Object> written = new LinkedHashMap<>();
            scrub(runtime, attributes(key, "ivan@example.com"), true, null, written);

            // then
            assertThat(written).isEmpty();
            assertThat(runtime.failures()).isEqualTo(1);
            assertThat(runtime.attributesVerified()).isFalse();
            assertThat(runtime.collapseModeActivations()).isEqualTo(1);
            assertThat(runtime.maskedActions()).isZero();
        }
    }

    @Test
    @DisplayName("фатальная ошибка всплывает после fail-closed снимка")
    void fatalMutationFailureCollapsesBeforeItPropagates() {
        // given
        AttributeKey<String> key = AttributeKey.stringKey("user.email");
        VirtualMachineError failure = new SyntheticVirtualMachineError();

        try (ScrubbingRuntime runtime = runtime(ScrubbingDecision.mask("current-behavior"))) {
            // when
            Map<AttributeKey<?>, Object> written = new LinkedHashMap<>();
            Throwable thrown;
            try {
                scrub(runtime, attributes(key, "ivan@example.com"), false, failure, written);
                thrown = null;
            } catch (Throwable caught) {
                thrown = caught;
            }

            // then
            assertThat(thrown).isSameAs(failure);
            assertThat(written.get(key)).isEqualTo(FAILED);
            assertThat(runtime.failures()).isEqualTo(1);
            assertThat(runtime.attributesVerified()).isFalse();
            assertThat(runtime.collapseModeActivations()).isEqualTo(1);
            assertThat(runtime.maskedActions()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("повторный markAttributesUnverified не увеличивает collapse")
    void repeatedUnverifiedMarkKeepsCollapseActivationsAtOne() {
        // given
        try (ScrubbingRuntime runtime = runtime(ScrubbingDecision.keep())) {
            // when
            runtime.markAttributesUnverified();
            runtime.markAttributesUnverified();
            runtime.recordCollapsedSpan();

            // then
            assertThat(runtime.attributesVerified()).isFalse();
            assertThat(runtime.collapseModeActivations()).isEqualTo(1);
            assertThat(runtime.collapsedSpans()).isEqualTo(1);
        }
    }

    @Test
    void alreadyMaskedScalarAndNumericSentinelDoNotWriteOrCountAgain() {
        // given
        AttributeKey<String> text = AttributeKey.stringKey("text.secret");
        AttributeKey<Long> number = AttributeKey.longKey("number.secret");
        try (ScrubbingRuntime runtime = runtime(ScrubbingDecision.mask("test"))) {
            // when
            Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(text, "***", number, 0L));

            // then
            assertThat(written).isEmpty();
            assertThat(runtime.maskedActions()).isZero();
            assertThat(runtime.failures()).isZero();
        }
    }

    @Test
    void malformedArrayFromKeyBasedHashIsMaskedWithoutHashingItsSafePrefix() {
        // given
        AttributeKey<List<String>> key = AttributeKey.stringArrayKey("user.identity");
        try (ScrubbingRuntime runtime = runtime(ScrubbingDecision.hash("test"))) {
            // when
            Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(key, List.of("raw-secret", 42L)));

            // then
            assertThat(written.get(key)).isEqualTo(List.of("***"));
            assertThat(runtime.maskedActions()).isEqualTo(1L);
            assertThat(runtime.hashedActions()).isZero();
            assertThat(runtime.failures()).isEqualTo(1L);
            assertThat(runtime.attributesVerified()).isTrue();
        }
    }

    @Test
    void oversizedArrayFromKeyBasedHashIsRejectedWithoutReadingElements() {
        // given
        AttributeKey<List<String>> key = AttributeKey.stringArrayKey("user.identity");
        List<String> inaccessible = new AbstractList<>() {
            @Override
            public String get(int index) {
                throw new AssertionError("Oversized array elements must not be read");
            }

            @Override
            public int size() {
                return 33;
            }
        };
        try (ScrubbingRuntime runtime = runtime(ScrubbingDecision.hash("test"))) {
            // when
            Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(key, inaccessible));

            // then
            assertThat(written.get(key)).isEqualTo(List.of("***"));
            assertThat(runtime.maskedActions()).isEqualTo(1L);
            assertThat(runtime.hashedActions()).isZero();
            assertThat(runtime.failures()).isZero();
            assertThat(runtime.attributesVerified()).isTrue();
        }
    }

    @Test
    void inaccessibleArraySizeCollapsesAndCountsOnlyTheSuccessfulFallbackWrite() {
        // given
        AttributeKey<List<String>> key = AttributeKey.stringArrayKey("user.identity");
        List<String> inaccessible = new AbstractList<>() {
            @Override
            public String get(int index) {
                throw new AssertionError("Array elements must not be read");
            }

            @Override
            public int size() {
                throw new IllegalStateException("Array size unavailable");
            }
        };
        try (ScrubbingRuntime runtime = runtime(ScrubbingDecision.hash("test"))) {
            // when
            Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(key, inaccessible));

            // then
            assertThat(written.get(key)).isEqualTo(List.of(FAILED));
            assertThat(runtime.maskedActions()).isEqualTo(1L);
            assertThat(runtime.hashedActions()).isZero();
            assertThat(runtime.failures()).isEqualTo(1L);
            assertThat(runtime.attributesVerified()).isFalse();
            assertThat(runtime.collapseModeActivations()).isEqualTo(1L);
        }
    }

    private static ScrubbingRuntime runtime(ScrubbingDecision decision) {
        return runtime(decision, MaskedSecret.of("test-key"));
    }

    private static ScrubbingRuntime runtime(ScrubbingDecision decision, MaskedSecret hmacKey) {
        return ScrubbingRuntimeTestFixtures.fromBuiltInRules(List.of(constantDecision(decision)), hmacKey, false);
    }

    private static SpanAttributeScrubbingRule constantDecision(ScrubbingDecision decision) {
        return new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return "current-behavior";
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
                return decision;
            }
        };
    }

    private static Attributes attributes(AttributeKey<?> key, Object value) {
        return sequenced(List.of(Map.entry(key, value)));
    }

    private static Attributes attributes(AttributeKey<?> firstKey, Object firstValue,
                                         AttributeKey<?> secondKey, Object secondValue) {
        return sequenced(List.of(Map.entry(firstKey, firstValue), Map.entry(secondKey, secondValue)));
    }

    private static Attributes sequenced(List<Map.Entry<AttributeKey<?>, Object>> entries) {
        Attributes snapshot = mock(Attributes.class);
        doAnswer(invocation -> {
            BiConsumer<AttributeKey<?>, Object> consumer = invocation.getArgument(0);
            for (Map.Entry<AttributeKey<?>, Object> entry : entries) {
                consumer.accept(entry.getKey(), entry.getValue());
            }
            return null;
        }).when(snapshot).forEach(any());
        return snapshot;
    }

    private static ReadWriteSpan failFirstMutation(ReadWriteSpan delegate) {
        boolean[] failed = {false};
        return (ReadWriteSpan) Proxy.newProxyInstance(
                ScrubbingRuntimeCurrentBehaviorTest.class.getClassLoader(),
                new Class<?>[] {ReadWriteSpan.class},
                (proxy, method, args) -> {
                    if ("setAttribute".equals(method.getName()) && !failed[0]) {
                        failed[0] = true;
                        throw new IllegalStateException("synthetic mutation failure");
                    }

                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static final class SyntheticVirtualMachineError extends VirtualMachineError {
        private SyntheticVirtualMachineError() {
            super("synthetic");
        }
    }

    private static Map<AttributeKey<?>, Object> scrub(ScrubbingRuntime runtime, Attributes snapshot) {
        Map<AttributeKey<?>, Object> written = new LinkedHashMap<>();
        scrub(runtime, snapshot, false, null, written);
        return written;
    }

    private static void scrub(ScrubbingRuntime runtime, Attributes snapshot, boolean snapshotUnavailable,
                              Throwable setAttributeFailure, Map<AttributeKey<?>, Object> written) {
        ReadWriteSpan span = mock(ReadWriteSpan.class);
        if (snapshotUnavailable) {
            when(span.getAttributes()).thenThrow(new IllegalStateException("snapshot unavailable"));
        } else {
            when(span.getAttributes()).thenReturn(snapshot);
        }

        boolean[] failed = {false};
        doAnswer(invocation -> {
            if (!failed[0] && setAttributeFailure != null) {
                failed[0] = true;
                throw setAttributeFailure;
            }

            AttributeKey<?> key = invocation.getArgument(0);
            written.put(key, invocation.getArgument(1));
            return span;
        }).when(span).setAttribute(ArgumentMatchers.<AttributeKey<Object>>any(), any());
        runtime.scrubSpanAttributes(span);
    }

    private static SdkTracerProvider realSpanProvider() {
        return SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(SpanExporter.noop()))
                .build();
    }
}
