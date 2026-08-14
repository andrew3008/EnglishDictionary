package space.br1440.platform.tracing.otel.javaagent.exporter;

import java.util.List;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5 tests for {@link BoundaryTextPolicy}: structural text (span/event name, status description)
 * uses only {@code BUILT_IN} rules, and forbidden actions (HASH, unbounded TRUNCATE) always
 * downgrade to a safe sentinel — never {@code KEEP}, never a raw HMAC digest.
 */
class BoundaryTextPolicyTest {

    private static final SpanAttributeScrubbingRule EMAIL = BuiltInSpanAttributeScrubbingRules.resolve("email");
    /** Value-based (unlike key-based {@code password}), so it can match structural-text content directly. */
    private static final SpanAttributeScrubbingRule JWT = BuiltInSpanAttributeScrubbingRules.resolve("jwt");
    /** {@code ip-address} is key-based and can never match text content; this fixture exercises the
     *  unbounded-TRUNCATE downgrade path directly, using the exact reason IpAddressRule would use. */
    private static final SpanAttributeScrubbingRule UNBOUNDED_TRUNCATE = new SpanAttributeScrubbingRule() {
        @Nonnull
        @Override
        public String name() {
            return "test-unbounded-truncate";
        }

        @Nonnull
        @Override
        public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
            return ScrubbingDecision.truncate(BuiltInSpanAttributeScrubbingRules.IP_ADDRESS.configName(), -1);
        }
    };

    @Test
    @DisplayName("scrubName: null/empty input is returned as-is (never crashes, never redacted needlessly)")
    void scrubNamePassesThroughNullAndEmpty() {
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(EMAIL));

        assertThat(BoundaryTextPolicy.scrubSpanName(null, runtime).value()).isNull();
        assertThat(BoundaryTextPolicy.scrubSpanName("", runtime).value()).isEmpty();
    }

    @Test
    @DisplayName("scrubName: KEEP leaves the name untouched")
    void scrubNameKeepsSafeNames() {
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(EMAIL));

        assertThat(BoundaryTextPolicy.scrubSpanName("GET /orders/{id}", runtime).value()).isEqualTo("GET /orders/{id}");
    }

    @Test
    @DisplayName("scrubName: HASH is forbidden for names and downgrades to <redacted>, never a raw digest")
    void scrubNameDowngradesHashToRedacted() {
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(EMAIL), "some-hmac-key");

        String result = BoundaryTextPolicy.scrubSpanName("contact ivan@example.com", runtime).value();

        assertThat(result).isEqualTo(BoundaryTextPolicy.REDACTED);
        assertThat(runtime.boundaryHashDowngradedToMaskAdder().sum()).isEqualTo(1L);
    }

    @Test
    @DisplayName("scrubName: DROP never produces an empty/null name, downgrades to <redacted>")
    void scrubNameDowngradesDropToRedactedNeverEmpty() {
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(JWT));

        String result = BoundaryTextPolicy.scrubSpanName("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.signature", runtime).value();

        assertThat(result).isEqualTo(BoundaryTextPolicy.REDACTED).isNotEmpty();
    }

    @Test
    @DisplayName("scrubName: unbounded TRUNCATE (IP prefix rule) downgrades to <redacted>")
    void scrubNameDowngradesIpTruncateToRedacted() {
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(UNBOUNDED_TRUNCATE));

        String result = BoundaryTextPolicy.scrubSpanName("connect from 10.1.2.3", runtime).value();

        assertThat(result).isEqualTo(BoundaryTextPolicy.REDACTED);
        assertThat(runtime.boundaryTruncateDowngradedToMaskAdder().sum()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Span name and event name are distinct rule keys, not one shared 'span.name'")
    void spanAndEventNamesAreAddressedByDistinctRuleKeys() {
        SpanAttributeScrubbingRule eventNameOnly = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return "test-event-name-only";
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
                return BoundaryTextPolicy.EVENT_NAME_KEY.equals(key)
                        ? ScrubbingDecision.mask(name())
                        : ScrubbingDecision.keep();
            }
        };
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(eventNameOnly));

        assertThat(BoundaryTextPolicy.scrubEventName("exception", runtime).value())
                .isEqualTo(BoundaryTextPolicy.REDACTED);
        assertThat(BoundaryTextPolicy.scrubSpanName("GET /orders", runtime).value())
                .as("правило для event.name не должно затрагивать имя span'а")
                .isEqualTo("GET /orders");
    }

    @Test
    @DisplayName("scrubStatusDescription: DROP becomes empty string, distinct from name's <redacted>")
    void scrubStatusDescriptionDropBecomesEmpty() {
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(JWT));

        String result = BoundaryTextPolicy.scrubStatusDescription(
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.signature", runtime).value();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("scrubStatusDescription: HASH is forbidden and downgrades to <redacted>")
    void scrubStatusDescriptionDowngradesHashToRedacted() {
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(EMAIL), "some-hmac-key");

        String result = BoundaryTextPolicy.scrubStatusDescription("failed for ivan@example.com", runtime).value();

        assertThat(result).isEqualTo(BoundaryTextPolicy.REDACTED);
    }

    @Test
    @DisplayName("No built-in text rules configured: text passes through unchanged")
    void noBuiltInTextRulesMeansIdentity() {
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of());

        assertThat(BoundaryTextPolicy.scrubSpanName("contact ivan@example.com", runtime).value())
                .isEqualTo("contact ivan@example.com");
    }

    @Test
    @DisplayName("BoundaryTextPolicy ignores BUNDLED_SPI and EXTERNAL_EXTENSION rules")
    void nonBuiltInOriginsNeverScrubStructuralText() {
        SpanAttributeScrubbingRule external = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return "external-mask-everything";
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
                return ScrubbingDecision.mask(name());
            }
        };
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(), List.of(external), null);

        assertThat(BoundaryTextPolicy.scrubSpanName("contact ivan@example.com", runtime).value())
                .isEqualTo("contact ivan@example.com");
        assertThat(BoundaryTextPolicy.scrubStatusDescription("failed for ivan@example.com", runtime).value())
                .isEqualTo("failed for ivan@example.com");
    }
}
