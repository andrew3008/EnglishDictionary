package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;

class BoundaryTextPolicyTest {

    private static final SpanAttributeScrubbingRule EMAIL = BuiltInSpanAttributeScrubbingRules.EMAIL.create();
    private static final SpanAttributeScrubbingRule JWT = BuiltInSpanAttributeScrubbingRules.JWT.create();
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
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(EMAIL));

        // when/then
        assertThat(BoundaryTextPolicy.scrubSpanName(null, runtime).value()).isNull();
        assertThat(BoundaryTextPolicy.scrubSpanName("", runtime).value()).isEmpty();
    }

    @Test
    @DisplayName("scrubName: KEEP leaves the name untouched")
    void scrubNameKeepsSafeNames() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(EMAIL));

        // when/then
        assertThat(BoundaryTextPolicy.scrubSpanName("GET /orders/{id}", runtime).value()).isEqualTo("GET /orders/{id}");
    }

    @Test
    @DisplayName("scrubName: HASH is forbidden for names and downgrades to <redacted>, never a raw digest")
    void scrubNameDowngradesHashToRedacted() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(EMAIL), "some-hmac-key");

        // when
        String result = BoundaryTextPolicy.scrubSpanName("contact ivan@example.com", runtime).value();

        // then
        assertThat(result).isEqualTo(BoundaryTextPolicy.REDACTED);
        assertThat(runtime.boundaryMetrics().hashDowngradedToMask()).isEqualTo(1L);
    }

    @Test
    @DisplayName("scrubName: DROP never produces an empty/null name, downgrades to <redacted>")
    void scrubNameDowngradesDropToRedactedNeverEmpty() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(JWT));

        // when
        String result = BoundaryTextPolicy.scrubSpanName("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.signature", runtime).value();

        // then
        assertThat(result).isEqualTo(BoundaryTextPolicy.REDACTED).isNotEmpty();
    }

    @Test
    @DisplayName("scrubName: unbounded TRUNCATE (IP prefix rule) downgrades to <redacted>")
    void scrubNameDowngradesIpTruncateToRedacted() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(UNBOUNDED_TRUNCATE));

        // when
        String result = BoundaryTextPolicy.scrubSpanName("connect from 10.1.2.3", runtime).value();

        // then
        assertThat(result).isEqualTo(BoundaryTextPolicy.REDACTED);
        assertThat(runtime.boundaryMetrics().truncateDowngradedToMask()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Span name and event name are distinct rule keys, not one shared 'span.name'")
    void spanAndEventNamesAreAddressedByDistinctRuleKeys() {
        // given
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

        // when/then
        assertThat(BoundaryTextPolicy.scrubEventName("exception", runtime).value())
                .isEqualTo(BoundaryTextPolicy.REDACTED);
        assertThat(BoundaryTextPolicy.scrubSpanName("GET /orders", runtime).value())
                .as("правило для event.name не должно затрагивать имя span'а")
                .isEqualTo("GET /orders");
    }

    @Test
    @DisplayName("scrubStatusDescription: DROP becomes empty string, distinct from name's <redacted>")
    void scrubStatusDescriptionDropBecomesEmpty() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(JWT));

        // when
        String result = BoundaryTextPolicy.scrubStatusDescription(
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.signature", runtime).value();

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("scrubStatusDescription: HASH is forbidden and downgrades to <redacted>")
    void scrubStatusDescriptionDowngradesHashToRedacted() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(EMAIL), "some-hmac-key");

        // when
        String result = BoundaryTextPolicy.scrubStatusDescription("failed for ivan@example.com", runtime).value();

        // then
        assertThat(result).isEqualTo(BoundaryTextPolicy.REDACTED);
    }

    @Test
    @DisplayName("No built-in text rules configured: text passes through unchanged")
    void noBuiltInTextRulesMeansIdentity() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of());

        // when/then
        assertThat(BoundaryTextPolicy.scrubSpanName("contact ivan@example.com", runtime).value())
                .isEqualTo("contact ivan@example.com");
    }

    @Test
    @DisplayName("BoundaryTextPolicy ignores BUNDLED_SPI and EXTERNAL_EXTENSION rules")
    void nonBuiltInOriginsNeverScrubStructuralText() {
        // given
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

        // when/then
        assertThat(BoundaryTextPolicy.scrubSpanName("contact ivan@example.com", runtime).value())
                .isEqualTo("contact ivan@example.com");
        assertThat(BoundaryTextPolicy.scrubStatusDescription("failed for ivan@example.com", runtime).value())
                .isEqualTo("failed for ivan@example.com");
    }
}
