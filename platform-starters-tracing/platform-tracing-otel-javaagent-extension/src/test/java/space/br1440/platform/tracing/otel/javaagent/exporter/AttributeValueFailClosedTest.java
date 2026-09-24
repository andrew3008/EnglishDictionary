package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.KeyValue;
import io.opentelemetry.api.common.Value;

class AttributeValueFailClosedTest {

    private static final AttributeKey<Value<?>> SENSITIVE = AttributeKey.valueKey("db.password");
    private static final AttributeKey<Value<?>> NEUTRAL = AttributeKey.valueKey("http.route.detail");

    private static final SpanAttributeScrubbingRule PASSWORD = BuiltInSpanAttributeScrubbingRules.PASSWORD_KEY.create();
    private static final SpanAttributeScrubbingRule IP = BuiltInSpanAttributeScrubbingRules.IP_ADDRESS.create();

    private static SpanAttributeScrubbingRule alwaysMask() {
        return rule("test-always-mask", ScrubbingDecision.mask("test-always-mask"));
    }

    private static SpanAttributeScrubbingRule alwaysHash() {
        return rule("test-always-hash", ScrubbingDecision.hash("test-always-hash"));
    }

    private static SpanAttributeScrubbingRule rule(String name, ScrubbingDecision decision) {
        return new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return name;
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
                return decision;
            }
        };
    }

    static Stream<Arguments> valueShapes() {
        return Stream.of(
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
                        Value.of(Value.of(false), Value.empty()))
                ),
                Arguments.of("nested", Value.of(
                        KeyValue.of("outer", Value.of(
                                Value.of(Map.of("inner-password", Value.of("supersecret"))),
                                Value.of("plain".getBytes(StandardCharsets.UTF_8))))))
        );
    }

    @ParameterizedTest(name = "DROP removes the whole VALUE attribute: {0}")
    @MethodSource("valueShapes")
    void dropRemovesTheWholeValueAttribute(String shape, Value<?> value) {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(PASSWORD));
        Attributes input = Attributes.of(SENSITIVE, value);

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        assertThat(result.changed()).as(shape).isTrue();
        assertThat(result.attributes().asMap()).as(shape).doesNotContainKey(SENSITIVE);
        assertThat(renderedPayload(result.attributes())).as(shape).doesNotContain("supersecret");
    }

    @ParameterizedTest(name = "MASK also removes the whole VALUE attribute: {0}")
    @MethodSource("valueShapes")
    void maskRemovesTheWholeValueAttribute(String shape, Value<?> value) {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(alwaysMask()));
        Attributes input = Attributes.of(NEUTRAL, value);

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        assertThat(result.changed()).as(shape).isTrue();
        assertThat(result.attributes().asMap()).as(shape).doesNotContainKey(NEUTRAL);
    }

    @ParameterizedTest(name = "HASH also removes the whole VALUE attribute: {0}")
    @MethodSource("valueShapes")
    void hashRemovesTheWholeValueAttribute(String shape, Value<?> value) {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(alwaysHash()), "test-hmac-key");
        Attributes input = Attributes.of(NEUTRAL, value);

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        assertThat(result.changed()).as(shape).isTrue();
        assertThat(result.attributes().asMap()).as(shape).doesNotContainKey(NEUTRAL);
    }

    @ParameterizedTest(name = "TRUNCATE is not a KEEP-equivalent for VALUE: {0}")
    @MethodSource("valueShapes")
    void truncateRemovesTheWholeValueAttribute(String shape, Value<?> value) {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(IP));
        AttributeKey<Value<?>> key = AttributeKey.valueKey("client.address");
        Attributes input = Attributes.of(key, value);

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        assertThat(result.changed()).as(shape).isTrue();
        assertThat(result.attributes().asMap()).as(shape).doesNotContainKey(key);
    }

    @ParameterizedTest(name = "collapse replaces VALUE with the sentinel: {0}")
    @MethodSource("valueShapes")
    void collapseReplacesValueWithTheSentinel(String shape, Value<?> value) {
        // given
        Attributes input = Attributes.of(SENSITIVE, value);

        // when
        Attributes collapsed = AttributesScrubber.collapseToSentinel(input);

        // then
        assertThat(collapsed.get(SENSITIVE))
                .as(shape)
                .isEqualTo(Value.of(ScrubbingDecision.SCRUBBING_FAILED_REASON));
        assertThat(renderedPayload(collapsed)).as(shape).doesNotContain("supersecret");
    }

    @ParameterizedTest(name = "KEEP from the legacy rule engine does not authorize a VALUE attribute: {0}")
    @MethodSource("valueShapes")
    void keepCannotAuthorizeComplexValue(String shape, Value<?> value) {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(PASSWORD, IP));
        Attributes input = Attributes.of(NEUTRAL, value);

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        assertThat(result.changed()).as(shape).isTrue();
        assertThat(result.attributes().asMap()).as(shape).doesNotContainKey(NEUTRAL);
        assertThat(renderedPayload(result.attributes())).as(shape).doesNotContain("supersecret");
    }

    @Test
    @DisplayName("Neighbouring attributes survive the removal regardless of the position of the VALUE key")
    void neighbouringAttributesSurviveTheRemoval() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(PASSWORD));
        Attributes input = Attributes.builder()
                .put(AttributeKey.stringKey("a.first"), "keep-me")
                .put(SENSITIVE, Value.of("supersecret"))
                .put(AttributeKey.stringKey("z.last"), "keep-me-too")
                .build();

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        assertThat(result.attributes().asMap()).doesNotContainKey(SENSITIVE);
        assertThat(result.attributes().get(AttributeKey.stringKey("a.first"))).isEqualTo("keep-me");
        assertThat(result.attributes().get(AttributeKey.stringKey("z.last"))).isEqualTo("keep-me-too");
    }

    @Test
    @DisplayName("VALUE remains fail-closed when no attribute rules are configured")
    void valueIsRemovedWithoutConfiguredRules() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of());
        Attributes input = Attributes.of(NEUTRAL, Value.of("supersecret"));

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        assertThat(result.changed()).isTrue();
        assertThat(result.attributes().get(NEUTRAL)).isNull();
        assertThat(renderedPayload(result.attributes())).doesNotContain("supersecret");
        assertThat(runtime.boundaryMetrics().valueAttributesRemoved()).isEqualTo(1L);
    }

    private static String renderedPayload(Attributes attributes) {
        StringBuilder rendered = new StringBuilder();
        attributes.forEach((key, value) -> {
            rendered.append(key.getKey()).append('=');
            if (value instanceof Value<?> typed) {
                rendered.append(typed.asString());
            } else {
                rendered.append(value);
            }
            rendered.append('\n');
        });
        return rendered.toString();
    }
}
