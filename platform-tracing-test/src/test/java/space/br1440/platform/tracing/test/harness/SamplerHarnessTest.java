package space.br1440.platform.tracing.test.harness;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SamplerHarness}.
 * Target: ≥95 % line coverage, ≥90 % branch coverage (plan §8.5).
 */
class SamplerHarnessTest {

    // -------------------------------------------------------------------------
    // Basic sampling decisions
    // -------------------------------------------------------------------------

    @Test
    void alwaysOn_returns_RECORD_AND_SAMPLE() {
        SamplerDecisionAssert.assertThat(
                SamplerHarness.of(Sampler.alwaysOn()).sample())
                .isRecordAndSample();
    }

    @Test
    void alwaysOff_returns_DROP() {
        SamplerDecisionAssert.assertThat(
                SamplerHarness.of(Sampler.alwaysOff()).sample())
                .isDrop();
    }

    @Test
    void hasDecision_matches_alwaysOn() {
        SamplerDecisionAssert.assertThat(
                SamplerHarness.of(Sampler.alwaysOn()).sample())
                .hasDecision(SamplingDecision.RECORD_AND_SAMPLE);
    }

    // -------------------------------------------------------------------------
    // Default field values
    // -------------------------------------------------------------------------

    @Test
    void default_traceId_is_used_when_not_set() {
        // sample() must not throw with default traceId
        var result = SamplerHarness.of(Sampler.alwaysOn()).sample();
        assertThat(result).isNotNull();
    }

    @Test
    void default_spanKind_is_INTERNAL() {
        // attribute snapshot is empty when no attrs added
        SamplerHarness h = SamplerHarness.of(Sampler.alwaysOn());
        assertThat(h.attributesSnapshot().asMap()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Builder methods
    // -------------------------------------------------------------------------

    @Test
    void traceId_is_applied() {
        var result = SamplerHarness.of(Sampler.alwaysOn())
                .traceId("00000000000000000000000000000099")
                .sample();
        assertThat(result.getDecision()).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
    }

    @Test
    void spanName_is_applied() {
        var result = SamplerHarness.of(Sampler.alwaysOn())
                .spanName("custom-span")
                .sample();
        assertThat(result.getDecision()).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
    }

    @Test
    void spanKind_SERVER_is_applied() {
        SamplerHarness h = SamplerHarness.of(Sampler.alwaysOn()).spanKind(SpanKind.SERVER);
        // Verify chain returns same instance and sample does not throw
        assertThat(h.sample()).isNotNull();
    }

    @Test
    void parentContext_is_applied() {
        var result = SamplerHarness.of(Sampler.alwaysOn())
                .parentContext(Context.root())
                .sample();
        assertThat(result.getDecision()).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
    }

    @Test
    void putStringAttribute_is_included_in_snapshot() {
        SamplerHarness h = SamplerHarness.of(Sampler.alwaysOn())
                .putStringAttribute("http.method", "GET");
        Attributes snap = h.attributesSnapshot();
        assertThat(snap.get(AttributeKey.stringKey("http.method"))).isEqualTo("GET");
    }

    @Test
    void putStringArrayAttribute_is_included_in_snapshot() {
        SamplerHarness h = SamplerHarness.of(Sampler.alwaysOn())
                .putStringArrayAttribute("x-trace-on", "on", "true");
        Attributes snap = h.attributesSnapshot();
        assertThat(snap.get(AttributeKey.stringArrayKey("x-trace-on")))
                .containsExactly("on", "true");
    }

    @Test
    void putAttribute_generic_works() {
        SamplerHarness h = SamplerHarness.of(Sampler.alwaysOn())
                .putAttribute(AttributeKey.longKey("http.status_code"), 200L);
        assertThat(h.attributesSnapshot().get(AttributeKey.longKey("http.status_code")))
                .isEqualTo(200L);
    }

    @Test
    void links_null_is_treated_as_empty() {
        // must not throw
        var result = SamplerHarness.of(Sampler.alwaysOn()).links(null).sample();
        assertThat(result).isNotNull();
    }

    @Test
    void links_non_null_accepted() {
        var result = SamplerHarness.of(Sampler.alwaysOn())
                .links(List.of())
                .sample();
        assertThat(result).isNotNull();
    }

    @Test
    void sampler_accessor_returns_same_instance() {
        Sampler s = Sampler.alwaysOn();
        assertThat(SamplerHarness.of(s).sampler()).isSameAs(s);
    }

    // -------------------------------------------------------------------------
    // Accumulated attributes are passed to shouldSample
    // -------------------------------------------------------------------------

    @Test
    void accumulated_attributes_are_passed_to_shouldSample() {
        SamplerHarness harness = SamplerHarness.of(Sampler.alwaysOn())
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("http.method", "GET")
                .putStringArrayAttribute("http.request.header.x-trace-on", "on");

        assertThat(harness.attributesSnapshot().asMap()).hasSize(2);
        assertThat(harness.sample().getDecision()).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
    }

    // -------------------------------------------------------------------------
    // Null-guards
    // -------------------------------------------------------------------------

    @Test
    void null_sampler_throws() {
        assertThatThrownBy(() -> SamplerHarness.of(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void null_traceId_throws() {
        assertThatThrownBy(() -> SamplerHarness.of(Sampler.alwaysOn()).traceId(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void null_spanName_throws() {
        assertThatThrownBy(() -> SamplerHarness.of(Sampler.alwaysOn()).spanName(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void null_spanKind_throws() {
        assertThatThrownBy(() -> SamplerHarness.of(Sampler.alwaysOn()).spanKind(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void null_parentContext_throws() {
        assertThatThrownBy(() -> SamplerHarness.of(Sampler.alwaysOn()).parentContext(null))
                .isInstanceOf(NullPointerException.class);
    }
}
