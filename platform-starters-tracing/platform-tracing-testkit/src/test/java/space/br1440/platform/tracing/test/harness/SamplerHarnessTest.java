package space.br1440.platform.tracing.test.harness;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SamplerHarnessTest {

    private final Sampler alwaysOn = Sampler.alwaysOn();
    private final Sampler alwaysOff = Sampler.alwaysOff();

    @Test
    void alwaysOn_returns_RECORD_AND_SAMPLE() {
        SamplerDecisionAssert.assertThat(SamplerHarness.of(alwaysOn).sample())
                .isRecordAndSample();
    }

    @Test
    void alwaysOff_returns_DROP() {
        SamplerDecisionAssert.assertThat(SamplerHarness.of(alwaysOff).sample())
                .isDrop();
    }

    @Test
    void accumulated_attributes_are_passed_to_sampler() {
        SamplerHarness harness = SamplerHarness.of(alwaysOn)
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("http.method", "GET")
                .putStringArrayAttribute("http.request.header.x-trace-on", "on");

        assertThat(harness.attributesSnapshot().asMap()).hasSize(2);
        assertThat(harness.sample().getDecision()).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
    }

    @Test
    void sample_with_span_name_assertSampled() {
        new SamplerHarness(alwaysOn)
                .sample("op")
                .assertSampled();
    }

    @Test
    void sample_with_span_name_assertDropped() {
        new SamplerHarness(alwaysOff)
                .sample("op")
                .assertDropped();
    }

    @Test
    void hasDecision_passes_for_alwaysOn() {
        new SamplerHarness(alwaysOn)
                .sample("op")
                .hasDecision(SamplingDecision.RECORD_AND_SAMPLE);
    }

    @Test
    void assertSampled_fails_for_alwaysOff() {
        assertThatThrownBy(() ->
                new SamplerHarness(alwaysOff).sample("op").assertSampled()
        ).isInstanceOf(AssertionError.class);
    }

    @Test
    void withHeader_and_withUrlPath_fluent_chain() {
        new SamplerHarness(alwaysOn)
                .withHeader("X-Trace-On", "on")
                .withUrlPath("/actuator/health")
                .withKind(SpanKind.SERVER)
                .sample("GET /actuator/health")
                .assertSampled();

        SamplerHarness harness = new SamplerHarness(alwaysOn)
                .withHeader("X-Trace-On", "on")
                .withUrlPath("/actuator/health");
        assertThat(harness.attributesSnapshot().asMap()).hasSize(2);
    }

    @Test
    void result_exposes_raw_sampling_result() {
        assertThat(new SamplerHarness(alwaysOn).sample("op").result()).isNotNull();
    }

    @Test
    void null_sampler_throws() {
        assertThatThrownBy(() -> new SamplerHarness(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void null_kind_throws() {
        assertThatThrownBy(() -> new SamplerHarness(alwaysOn).withKind(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void null_parent_throws() {
        assertThatThrownBy(() -> new SamplerHarness(alwaysOn).withParent(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void reset_clears_attributes_and_kind() {
        SamplerHarness harness = new SamplerHarness(alwaysOn)
                .withHeader("X-Trace-On", "on")
                .withUrlPath("/some/path")
                .withKind(SpanKind.SERVER);

        harness.reset();

        assertThat(harness.attributesSnapshot().isEmpty()).isTrue();
        harness.sample("clean-op").assertSampled();
    }
}
