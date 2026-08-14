package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.KeyValue;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;

/**
 * Fail-closed контракт для {@code AttributeType.VALUE} в processor-плоскости: атрибут нельзя
 * удалить из живого span, поэтому любое действие кроме {@code KEEP} заменяет всё дерево
 * {@link Value} единым низкокардинальным sentinel'ом.
 */
class ProcessorValueAttributeFailClosedTest {

    private static final AttributeKey<Value<?>> KEY = AttributeKey.valueKey("payload");
    private static final Value<String> SENTINEL = Value.of(ScrubbingDecision.SCRUBBING_FAILED_REASON);

    static Stream<Arguments> shapesAndActions() {
        List<Arguments> shapes = List.of(
                Arguments.of("empty", Value.empty()),
                Arguments.of("string", Value.of("supersecret")),
                Arguments.of("long", Value.of(42L)),
                Arguments.of("double", Value.of(1.5d)),
                Arguments.of("boolean", Value.of(true)),
                Arguments.of("bytes", Value.of("supersecret".getBytes(StandardCharsets.UTF_8))),
                Arguments.of("string-array", Value.of(Value.of("supersecret"), Value.of("second"))),
                Arguments.of("long-array", Value.of(Value.of(7L), Value.of(8L))),
                Arguments.of("double-array", Value.of(Value.of(1.5d), Value.of(2.5d))),
                Arguments.of("boolean-array", Value.of(Value.of(true), Value.of(false))),
                Arguments.of("heterogeneous-array", Value.of(Value.of("supersecret"), Value.of(7L))),
                Arguments.of("kv-list", Value.of(Map.of("password", Value.of("supersecret")))),
                Arguments.of("nested-array", Value.of(
                        Value.of(Value.of("supersecret"), Value.of(7L)),
                        Value.of(Value.of(false), Value.empty()))),
                Arguments.of("nested", Value.of(
                        KeyValue.of("outer", Value.of(
                                Value.of(Map.of("inner", Value.of("supersecret"))),
                                Value.of(new byte[]{1, 2, 3})))))
        );

        List<ScrubbingAction> actions = List.of(
                ScrubbingAction.MASK, ScrubbingAction.DROP, ScrubbingAction.HASH, ScrubbingAction.TRUNCATE);

        return shapes.stream().flatMap(shape -> actions.stream()
                .map(action -> Arguments.of(shape.get()[0] + "/" + action, shape.get()[1], action)));
    }

    @ParameterizedTest(name = "VALUE collapses to the sentinel: {0}")
    @MethodSource("shapesAndActions")
    void everyNonKeepActionCollapsesTheWholeValue(String label, Value<?> value, ScrubbingAction action) {
        // given
        ReadWriteSpan span = spanWith(Attributes.of(KEY, value));

        // when
        new ScrubbingSpanProcessor(List.of(ruleReturning(action))).onEnding(span);

        // then
        verify(span).setAttribute(KEY, SENTINEL);
    }

    @Test
    @DisplayName("Real SDK span exports a sentinel without retaining a complex VALUE secret")
    void realSdkSpanExportsSentinelWithoutRetainingComplexValueSecret() {
        // given
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        ScrubbingSpanProcessor processor = new ScrubbingSpanProcessor(List.of(ruleReturning(ScrubbingAction.MASK)));

        // when
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(processor)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            provider.get("value-contract-test")
                    .spanBuilder("value-contract")
                    .setAttribute(KEY, Value.of(Map.of("password", Value.of("supersecret"))))
                    .startSpan()
                    .end();

            // then
            assertThat(exporter.getFinishedSpanItems()).singleElement().satisfies(span -> {
                assertThat(span.getAttributes().asMap().keySet())
                        .extracting(AttributeKey::getKey)
                        .contains("payload");
                assertThat(span.getAttributes().asMap().values())
                        .allSatisfy(value -> assertThat(render(value)).doesNotContain("supersecret"));
            });
        }
    }

    @Test
    @DisplayName("KEEP from the legacy rule engine cannot authorize a VALUE attribute; it is never even consulted")
    void keepCannotAuthorizeValueAttributes() {
        // AttributeType.VALUE проверяется раньше вызова движка правил (C-2): старый
        // SpanAttributeScrubbingRule SPI не умеет анализировать Value<?> по листьям, поэтому его
        // KEEP недостоверен и весь атрибут схлопывается в sentinel независимо от решения правила.
        ReadWriteSpan span = spanWith(Attributes.of(KEY, Value.of("harmless")));

        new ScrubbingSpanProcessor(List.of(ruleReturning(null))).onEnding(span);

        verify(span).setAttribute(KEY, SENTINEL);
    }

    @Test
    @DisplayName("VALUE collapses to the sentinel even when the only registered rule would have "
            + "reported a fail-closed failure — the rule engine is never consulted for VALUE")
    void valueCollapsesWithoutConsultingARuleThatWouldHaveReportedFailure() {
        SpanAttributeScrubbingRule failing = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return "test-failing";
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
                return ScrubbingDecision.mask(ScrubbingDecision.SCRUBBING_FAILED_REASON);
            }
        };
        ReadWriteSpan span = spanWith(Attributes.of(KEY, Value.of("supersecret")));

        ScrubbingSpanProcessor processor = new ScrubbingSpanProcessor(List.of(failing));
        processor.onEnding(span);

        verify(span).setAttribute(KEY, SENTINEL);
        assertThat(processor.runtime().failures())
                .as("VALUE is intercepted before MergeEngine runs; the rule is never consulted")
                .isZero();
    }

    private static ReadWriteSpan spanWith(Attributes attributes) {
        ReadWriteSpan span = mock(ReadWriteSpan.class);
        SpanData spanData = mock(SpanData.class);
        when(span.toSpanData()).thenReturn(spanData);
        when(spanData.getAttributes()).thenReturn(attributes);
        return span;
    }

    private static String render(Object value) {
        return (value instanceof Value<?> typed) ? typed.asString() : String.valueOf(value);
    }

    /** @param action {@code null} — правило возвращает {@code KEEP} */
    private static SpanAttributeScrubbingRule ruleReturning(ScrubbingAction action) {
        ScrubbingDecision decision = switch (action == null ? ScrubbingAction.KEEP : action) {
            case MASK -> ScrubbingDecision.mask("test-rule");
            case DROP -> ScrubbingDecision.drop("test-rule");
            case HASH -> ScrubbingDecision.hash("test-rule");
            case TRUNCATE -> ScrubbingDecision.truncate("test-rule", 4);
            case KEEP -> ScrubbingDecision.keep();
        };

        return new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return "test-rule";
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
                return decision;
            }
        };
    }
}
