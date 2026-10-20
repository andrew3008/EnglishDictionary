package space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.common.util.ArrayUtils;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;

class ScrubbingRuntimeCurrentBehaviorTest {

    private static final String FAILED = ScrubbingDecision.SCRUBBING_FAILED_REASON;

    @Test
    @DisplayName("текущее поведение: MASK/DROP/HASH числа и boolean увеличивают droppedActions")
    void numericAndBooleanActionsIncrementDroppedActions() {
        // given
        AttributeKey<Long> longKey = AttributeKey.longKey("geo.lat");
        AttributeKey<Double> doubleKey = AttributeKey.doubleKey("geo.lon");
        AttributeKey<Boolean> booleanKey = AttributeKey.booleanKey("geo.flag");

        // when
        ScrubbingRuntime maskedRuntime = runtime(ScrubbingDecision.mask("current-behavior"));
        Map<AttributeKey<?>, Object> masked = scrub(maskedRuntime, attributes(longKey, 55L));
        ScrubbingRuntime dropped = runtime(ScrubbingDecision.drop("current-behavior"));
        Map<AttributeKey<?>, Object> droppedValues = scrub(dropped, attributes(doubleKey, 1.5d));
        ScrubbingRuntime hashed = runtime(ScrubbingDecision.hash("current-behavior"), null);
        Map<AttributeKey<?>, Object> hashedValues = scrub(hashed, attributes(booleanKey, true));

        // then
        assertThat(masked.get(longKey)).isEqualTo(0L);
        assertThat(maskedRuntime.droppedActions()).isEqualTo(1);
        assertThat(maskedRuntime.maskedActions()).isZero();
        assertThat(droppedValues.get(doubleKey)).isEqualTo(0.0d);
        assertThat(hashedValues.get(booleanKey)).isEqualTo(false);
        assertThat(dropped.droppedActions()).isEqualTo(1);
        assertThat(dropped.maskedActions()).isZero();
        assertThat(hashed.droppedActions()).isEqualTo(1);
        assertThat(hashed.hashMissingKey()).isZero();
        assertThat(hashed.maskedActions()).isZero();
    }

    @Test
    @DisplayName("текущее поведение: массив чисел сохраняет длину при MASK")
    void numericArrayMaskKeepsLength() {
        // given
        AttributeKey<List<Long>> key = AttributeKey.longArrayKey("geo.parts");
        ScrubbingRuntime runtime = runtime(ScrubbingDecision.mask("current-behavior"));

        // when
        Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(key, List.of(7L, 8L, 9L)));

        // then
        assertThat(written.get(key)).isEqualTo(List.of(0L, 0L, 0L));
        assertThat(runtime.droppedActions()).isEqualTo(1);
        assertThat(runtime.maskedActions()).isZero();
    }

    @Test
    @DisplayName("текущее поведение: TRUNCATE для числа и boolean оставляет значение")
    void truncateOnNumericAndBooleanKeepsTheValue() {
        // given
        AttributeKey<Long> longKey = AttributeKey.longKey("client.address");
        AttributeKey<Boolean> booleanKey = AttributeKey.booleanKey("client.address.internal");
        ScrubbingRuntime runtime = runtime(ScrubbingDecision.truncate("ip-address", 3));

        // when
        Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(longKey, 167838211L, booleanKey, true));

        // then
        assertThat(written).isEmpty();
        assertThat(runtime.truncatedActions()).isZero();
        assertThat(runtime.droppedActions()).isZero();
        assertThat(runtime.maskedActions()).isZero();
    }

    @Test
    @DisplayName("текущее поведение: HASH строки без ключа возвращает маску и считает оба счётчика")
    void hashWithoutKeyMasksAndCountsBoth() {
        // given
        AttributeKey<String> key = AttributeKey.stringKey("user.email");
        ScrubbingRuntime runtime = runtime(ScrubbingDecision.hash("current-behavior"), null);

        // when
        Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(key, "ivan@example.com"));

        // then
        assertThat(written.get(key)).isEqualTo("***");
        assertThat(runtime.hashMissingKey()).isEqualTo(1);
        assertThat(runtime.maskedActions()).isEqualTo(1);
        assertThat(runtime.hashedActions()).isZero();
    }

    @Test
    @DisplayName("текущее поведение: короткая строка TRUNCATE увеличивает truncatedActions без перезаписи")
    void shortTruncateStillIncrementsTruncatedActions() {
        // given
        AttributeKey<String> key = AttributeKey.stringKey("note");
        ScrubbingRuntime runtime = runtime(ScrubbingDecision.truncate("note", 100));

        // when
        Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(key, "short"));

        // then
        assertThat(written).doesNotContainKey(key);
        assertThat(runtime.truncatedActions()).isEqualTo(1);
    }

    @Test
    @DisplayName("текущее поведение: неудачное усечение IP возвращает маску и увеличивает failures")
    void failedIpTruncationMasksAndCountsFailure() {
        // given
        AttributeKey<String> key = AttributeKey.stringKey("client.address");
        ScrubbingRuntime runtime = runtime(ScrubbingDecision.truncate("ip-address", 64));

        // when
        Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(key, "not-an-ip"));

        // then
        assertThat(written.get(key)).isEqualTo("***");
        assertThat(runtime.truncatedActions()).isEqualTo(1);
        assertThat(runtime.failures()).isEqualTo(1);
    }

    @Test
    @DisplayName("текущее поведение: fail-closed схлопывает массив до одного sentinel")
    void failClosedCollapsesArrayToOneElement() {
        // given
        AttributeKey<List<Long>> key = AttributeKey.longArrayKey("secret.parts");
        ScrubbingRuntime runtime = runtime(ScrubbingDecision.scrubbingFailed());

        // when
        Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(key, List.of(7L, 8L, 9L)));

        // then
        assertThat(written.get(key)).isEqualTo(List.of(0L));
        assertThat(runtime.maskedActions()).isEqualTo(1);
        assertThat(runtime.attributesVerified()).isTrue();
    }

    @Test
    @DisplayName("текущее поведение: AttributeType.VALUE не изменяется")
    void valueAttributeIsLeftUntouched() {
        // given
        AttributeKey<Value<?>> key = AttributeKey.valueKey("payload");
        ScrubbingRuntime runtime = runtime(ScrubbingDecision.mask("current-behavior"));

        // when
        Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(key, Value.of("secret")));

        // then
        assertThat(written).doesNotContainKey(key);
        assertThat(key.getType()).isEqualTo(AttributeType.VALUE);
        assertThat(runtime.maskedActions()).isZero();
        assertThat(runtime.droppedActions()).isZero();
        assertThat(runtime.attributesVerified()).isTrue();
    }

    @Test
    void liveLookupsPreserveValueOriginAfterSnapshotNormalization() {
        // given
        AttributeKey<Value<?>> valueKey = AttributeKey.valueKey("payload");
        AttributeKey<String> typedKey = AttributeKey.stringKey("payload");

        try (SdkTracerProvider provider = SdkTracerProvider.builder().build()) {
            ReadWriteSpan valueOrigin = (ReadWriteSpan) provider.get("value-origin").spanBuilder("value-origin").startSpan();
            valueOrigin.setAttribute(valueKey, Value.of("secret"));
            ReadWriteSpan nativeOrigin = (ReadWriteSpan) provider.get("native-origin").spanBuilder("native-origin").startSpan();
            nativeOrigin.setAttribute(typedKey, "secret");

            // when
            Attributes valueSnapshot = valueOrigin.toSpanData().getAttributes();
            Attributes nativeSnapshot = nativeOrigin.toSpanData().getAttributes();

            // then
            assertThat(valueSnapshot.get(typedKey)).isEqualTo("secret");
            assertThat(valueOrigin.getAttribute(valueKey)).isEqualTo(Value.of("secret"));
            assertThat(valueOrigin.getAttribute(typedKey)).isNull();

            assertThat(nativeSnapshot.get(typedKey)).isEqualTo("secret");
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
        ScrubbingRuntime runtime = runtime(ScrubbingDecision.mask("test"));

        try (SdkTracerProvider provider = SdkTracerProvider.builder().build()) {
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
        ScrubbingRuntime runtime = runtime(ScrubbingDecision.mask("test"));

        try (SdkTracerProvider provider = SdkTracerProvider.builder().build()) {
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
            assertThat(runtime.maskedActions()).isEqualTo(2L);

            span.end();
        }
    }

    @Test
    @DisplayName("текущее поведение: null внутри STRING_ARRAY сохраняется на своей позиции")
    void stringArrayKeepsNullElement() {
        // given
        AttributeKey<List<String>> key = AttributeKey.stringArrayKey("tags");
        List<String> input = new ArrayList<>();
        input.add("alpha");
        input.add(null);
        input.add("beta");
        ScrubbingRuntime runtime = runtime(ScrubbingDecision.mask("current-behavior"));

        // when
        Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(key, input));

        // then
        assertThat(written.get(key)).isEqualTo(nullableList("***", null, "***"));
        assertThat(runtime.maskedActions()).isEqualTo(2);
    }

    @Test
    @DisplayName("текущее поведение: DROP строки пишет пустую строку и оставляет атрибут")
    void dropStringWritesEmptyAndKeepsTheAttribute() {
        // given
        AttributeKey<String> key = AttributeKey.stringKey("user.email");
        ScrubbingRuntime runtime = runtime(ScrubbingDecision.drop("current-behavior"));

        // when
        Map<AttributeKey<?>, Object> written = scrub(runtime, attributes(key, "ivan@example.com"));

        // then
        assertThat(written).containsKey(key);
        assertThat(written.get(key)).isEqualTo("");
        assertThat(runtime.droppedActions()).isEqualTo(1);
    }

    @Test
    @DisplayName("текущее поведение: исключение переписывает весь исходный снимок")
    void mutationFailureRewritesTheWholeSnapshot() {
        // given
        AttributeKey<String> first = AttributeKey.stringKey("first.secret");
        AttributeKey<String> second = AttributeKey.stringKey("second.secret");
        ScrubbingRuntime runtime = runtime(ScrubbingDecision.mask("current-behavior"));

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
        assertThat(runtime.maskedActions()).isEqualTo(3);
    }

    @Test
    @DisplayName("текущее поведение: недоступный снимок не трогает атрибуты и включает collapse")
    void unavailableSnapshotLeavesAttributesAndCollapses() {
        // given
        AttributeKey<String> key = AttributeKey.stringKey("user.email");
        ScrubbingRuntime runtime = runtime(ScrubbingDecision.mask("current-behavior"));

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

    @Test
    @DisplayName("текущее поведение: фатальная ошибка всплывает после fail-closed снимка")
    void fatalMutationFailureCollapsesBeforeItPropagates() {
        // given
        AttributeKey<String> key = AttributeKey.stringKey("user.email");
        ScrubbingRuntime runtime = runtime(ScrubbingDecision.mask("current-behavior"));
        VirtualMachineError failure = new SyntheticVirtualMachineError();

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
        assertThat(runtime.maskedActions()).isEqualTo(2);
    }

    @Test
    @DisplayName("текущее поведение: повторный markAttributesUnverified не увеличивает collapse")
    void repeatedUnverifiedMarkKeepsCollapseActivationsAtOne() {
        // given
        ScrubbingRuntime runtime = runtime(ScrubbingDecision.keep());

        // when
        runtime.markAttributesUnverified();
        runtime.markAttributesUnverified();
        runtime.recordCollapsedSpan();

        // then
        assertThat(runtime.attributesVerified()).isFalse();
        assertThat(runtime.collapseModeActivations()).isEqualTo(1);
        assertThat(runtime.collapsedSpans()).isEqualTo(1);
    }

    @SuppressWarnings("SameParameterValue")
    private static List<String> nullableList(String first, String second, String third) {
        List<String> values = new ArrayList<>();
        values.add(first);
        values.add(second);
        values.add(third);
        return values;
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
        SpanData spanData = mock(SpanData.class);
        when(spanData.getAttributes()).thenReturn(snapshot);
        boolean[] failed = {setAttributeFailure != null};
        ReadWriteSpan span = (ReadWriteSpan) Proxy.newProxyInstance(
                ScrubbingRuntimeCurrentBehaviorTest.class.getClassLoader(),
                new Class<?>[] {ReadWriteSpan.class},
                (proxy, method, args) -> {
                    if ("toSpanData".equals(method.getName()) && ArrayUtils.isNullOrEmpty(args)) {
                        if (snapshotUnavailable) {
                            throw new IllegalStateException("snapshot unavailable");
                        }
                        return spanData;
                    }
                    if ("setAttribute".equals(method.getName())
                            && args != null
                            && args.length == 2
                            && args[0] instanceof AttributeKey<?> key) {
                        if (failed[0] && setAttributeFailure != null) {
                            failed[0] = false;
                            throw setAttributeFailure;
                        }
                        written.put(key, args[1]);
                        return proxy;
                    }

                    switch (method.getName()) {
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "toString" -> {
                            return "RecordingAttributeSpan";
                        }
                    }

                    Class<?> returnType = method.getReturnType();
                    if (returnType.equals(boolean.class)) {
                        return false;
                    }

                    if (returnType.equals(int.class)) {
                        return 0;
                    }

                    if (returnType.equals(long.class)) {
                        return 0L;
                    }

                    if (Span.class.isAssignableFrom(returnType) || returnType.equals(ReadWriteSpan.class)) {
                        return proxy;
                    }

                    return null;
                });
        runtime.scrubSpanAttributes(span);
    }
}
