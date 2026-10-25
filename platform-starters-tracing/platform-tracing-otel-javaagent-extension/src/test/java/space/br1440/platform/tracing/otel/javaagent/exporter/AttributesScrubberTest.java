package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;

class AttributesScrubberTest {

    private static final SpanAttributeScrubbingRule EMAIL = BuiltInSpanAttributeScrubbingRules.EMAIL.create();
    private static final SpanAttributeScrubbingRule PASSWORD = BuiltInSpanAttributeScrubbingRules.PASSWORD_KEY.create();
    private static final SpanAttributeScrubbingRule IP = BuiltInSpanAttributeScrubbingRules.IP_ADDRESS.create();

    @Test
    @DisplayName("No rules configured: attributes pass through untouched, changed=false")
    void noRulesMeansIdentity() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of());
        Attributes input = Attributes.of(AttributeKey.stringKey("user.email"), "ivan@example.com");

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        assertThat(result.changed()).isFalse();
        assertThat(result.attributes()).isEqualTo(input);
    }

    @Test
    @DisplayName("DROP removes the attribute key entirely (nested surfaces do not use \"\")")
    void dropRemovesKey() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(PASSWORD));
        Attributes input = Attributes.of(
                AttributeKey.stringKey("db.password"), "supersecret",
                AttributeKey.stringKey("db.statement"), "SELECT 1");

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        assertThat(result.changed()).isTrue();
        assertThat(result.attributes().asMap()).doesNotContainKey(AttributeKey.stringKey("db.password"));
        assertThat(result.attributes().get(AttributeKey.stringKey("db.statement"))).isEqualTo("SELECT 1");
    }

    @Test
    @DisplayName("HASH without an HMAC key falls back to MASK (\"***\"), never keeps the raw value")
    void hashWithoutHmacKeyFallsBackToMask() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(EMAIL));
        Attributes input = Attributes.of(AttributeKey.stringKey("user.email"), "ivan@example.com");

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        assertThat(result.changed()).isTrue();
        assertThat(result.attributes().get(AttributeKey.stringKey("user.email"))).isEqualTo("***");
    }

    @Test
    @DisplayName("HASH with an HMAC key produces a deterministic digest, never the raw value")
    void hashWithHmacKeyProducesADigest() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(EMAIL), "test-hmac-key");
        Attributes input = Attributes.of(AttributeKey.stringKey("user.email"), "ivan@example.com");

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        String hashed = result.attributes().get(AttributeKey.stringKey("user.email"));
        assertThat(hashed).isNotEqualTo("ivan@example.com").isNotEqualTo("***");
        assertThat(hashed).isEqualTo(runtime.hmacHex("ivan@example.com"));
    }

    @Test
    @DisplayName("TRUNCATE (IP address) collapses to a /24-style prefix, same as span-attribute plane")
    void truncateAppliesIpPrefixTruncation() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(IP));
        Attributes input = Attributes.of(AttributeKey.stringKey("client.address"), "10.1.2.3");

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        assertThat(result.changed()).isTrue();
        assertThat(result.attributes().get(AttributeKey.stringKey("client.address"))).isEqualTo("10.1.2.0");
    }

    @Test
    @DisplayName("Non-string attribute types are collapsed to a typed sentinel for MASK/HASH decisions")
    void nonStringAttributesCollapseToSentinel() {
        // given
        SpanAttributeScrubbingRule alwaysMask = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return "test-always-mask";
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
                return ScrubbingDecision.mask(name());
            }
        };
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(alwaysMask));
        Attributes input = Attributes.of(AttributeKey.longKey("some.counter"), 123456L);

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        assertThat(result.changed()).isTrue();
        assertThat(result.attributes().get(AttributeKey.longKey("some.counter"))).isEqualTo(0L);
    }

    @Test
    @DisplayName("DROP on a non-string attribute removes the key entirely (contract is uniform across types)")
    void dropOnNonStringAttributeRemovesKey() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(PASSWORD));
        Attributes input = Attributes.of(AttributeKey.longKey("db.password"), 123456L);

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        assertThat(result.changed()).isTrue();
        assertThat(result.attributes().asMap()).doesNotContainKey(AttributeKey.longKey("db.password"));
    }

    @Test
    @DisplayName("DROP on a string-array attribute removes the key entirely")
    void dropOnStringArrayAttributeRemovesKey() {
        // given
        AttributeKey<List<String>> key = AttributeKey.stringArrayKey("db.password");
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(PASSWORD));
        Attributes input = Attributes.of(key, List.of("secret-one", "secret-two"));

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        assertThat(result.changed()).isTrue();
        assertThat(result.attributes().asMap()).doesNotContainKey(key);
    }

    @Test
    @DisplayName("TRUNCATE on a numeric attribute keeps the value: the matrix maps numeric TRUNCATE to KEEP")
    void truncateOnNumericAttributeKeepsTheValue() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(IP));
        Attributes input = Attributes.of(AttributeKey.longKey("client.address"), 167838211L);

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        assertThat(result.changed()).isFalse();
        assertThat(result.attributes())
                .as("a key-based TRUNCATE rule must not corrupt numeric values into a sentinel")
                .isSameAs(input);
        assertThat(result.attributes().get(AttributeKey.longKey("client.address"))).isEqualTo(167838211L);
    }

    @Test
    @DisplayName("TRUNCATE on a boolean attribute keeps the value")
    void truncateOnBooleanAttributeKeepsTheValue() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(IP));
        Attributes input = Attributes.of(AttributeKey.booleanKey("client.address.internal"), true);

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        assertThat(result.changed()).isFalse();
        assertThat(result.attributes().get(AttributeKey.booleanKey("client.address.internal"))).isTrue();
    }

    @Test
    @DisplayName("TRUNCATE keeps numeric and boolean arrays too, element-for-element")
    void truncateOnNumericAndBooleanArraysKeepsEveryElement() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(IP));
        AttributeKey<List<Long>> longs = AttributeKey.longArrayKey("client.address.parts");
        AttributeKey<List<Double>> doubles = AttributeKey.doubleArrayKey("client.address.weights");
        AttributeKey<List<Boolean>> booleans = AttributeKey.booleanArrayKey("client.address.flags");
        Attributes input = Attributes.builder()
                .put(longs, List.of(10L, 1L, 2L, 3L))
                .put(doubles, List.of(1.5d, 2.5d))
                .put(booleans, List.of(true, false))
                .build();

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        assertThat(result.changed()).isFalse();
        assertThat(result.attributes()).isSameAs(input);
        assertThat(result.attributes().get(longs)).containsExactly(10L, 1L, 2L, 3L);
        assertThat(result.attributes().get(doubles)).containsExactly(1.5d, 2.5d);
        assertThat(result.attributes().get(booleans)).containsExactly(true, false);
    }

    @Test
    @DisplayName("Non-TRUNCATE actions still collapse numeric arrays to a sentinel of the same length")
    void maskOnNumericArrayStillCollapsesToSentinel() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(PASSWORD));
        AttributeKey<List<Long>> longs = AttributeKey.longArrayKey("db.password.parts");
        Attributes input = Attributes.of(longs, List.of(7L, 8L, 9L));

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        assertThat(result.changed()).isTrue();
        assertThat(result.attributes().get(longs))
                .as("DROP на immutable поверхности удаляет ключ целиком")
                .isNull();
    }

    @Test
    @DisplayName("Clean attributes are returned as the same instance: no builder allocation on the clean path")
    void cleanAttributesArePassedThroughByIdentity() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(EMAIL, PASSWORD, IP));
        Attributes input = Attributes.of(
                AttributeKey.stringKey("http.route"), "/orders/{id}",
                AttributeKey.longKey("http.status_code"), 200L);

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        assertThat(result.changed()).isFalse();
        assertThat(result.attributes()).isSameAs(input);
    }

    @Test
    @DisplayName("Entries read before the first change are preserved when the lazy copy starts")
    void lazyCopyPreservesThePrefixReadBeforeTheFirstChange() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(PASSWORD));
        Attributes input = Attributes.builder()
                .put(AttributeKey.stringKey("a.first"), "keep-me")
                .put(AttributeKey.stringKey("db.password"), "supersecret")
                .put(AttributeKey.stringKey("z.last"), "keep-me-too")
                .build();

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(input, runtime);

        // then
        assertThat(result.changed()).isTrue();
        assertThat(result.attributes().get(AttributeKey.stringKey("a.first"))).isEqualTo("keep-me");
        assertThat(result.attributes().get(AttributeKey.stringKey("z.last"))).isEqualTo("keep-me-too");
        assertThat(result.attributes().asMap()).doesNotContainKey(AttributeKey.stringKey("db.password"));
    }

    @Test
    @DisplayName("Empty attributes short-circuit without allocating a builder")
    void emptyAttributesShortCircuit() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(EMAIL));

        // when
        AttributesScrubber.Result result = AttributesScrubber.scrub(Attributes.empty(), runtime);

        // then
        assertThat(result.changed()).isFalse();
        assertThat(result.attributes()).isEqualTo(Attributes.empty());
    }
}
