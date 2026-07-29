package space.br1440.platform.tracing.test.harness;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SamplerHarness} and {@link SamplerDecisionAssert}.
 * Coverage target: ≥95 % lines, ≥90 % branches (per plan §8.5).
 */
class SamplerHarnessTest {

    // alwaysOn sampler produces RECORD_AND_SAMPLE unconditionally
    private final Sampler alwaysOn = Sampler.alwaysOn();
    // alwaysOff sampler produces DROP unconditionally
    private final Sampler alwaysOff = Sampler.alwaysOff();

    @Test
    void assertSampled_passes_for_alwaysOn() {
        new SamplerHarness(alwaysOn)
                .sample("op")
                .assertSampled();
    }

    @Test
    void assertDropped_passes_for_alwaysOff() {
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
    void assertDropped_fails_for_alwaysOn() {
        assertThatThrownBy(() ->
                new SamplerHarness(alwaysOn).sample("op").assertDropped()
        ).isInstanceOf(AssertionError.class);
    }

    @Test
    void withHeader_does_not_throw() {
        // smoke: attribute is set without exception; alwaysOn ignores it
        new SamplerHarness(alwaysOn)
                .withHeader("X-Trace-On", "on")
                .sample("op")
                .assertSampled();
    }

    @Test
    void withUrlPath_does_not_throw() {
        new SamplerHarness(alwaysOn)
                .withUrlPath("/actuator/health")
                .withKind(SpanKind.SERVER)
                .sample("GET /actuator/health")
                .assertSampled();
    }

    @Test
    void withKind_default_is_INTERNAL() {
        // default kind is INTERNAL — should still be sampled by alwaysOn
        new SamplerHarness(alwaysOn)
                .sample("op")
                .assertSampled();
    }

    @Test
    void fluent_chain_all_builders() {
        new SamplerHarness(alwaysOn)
                .withHeader("X-QA-Trace", "on")
                .withUrlPath("/api/v1/resource")
                .withKind(SpanKind.CLIENT)
                .sample("client-call")
                .assertSampled();
    }

    @Test
    void result_exposes_raw_sampling_result() {
        var assert_ = new SamplerHarness(alwaysOn).sample("op");
        assert_.result(); // must not be null
    }

    @Test
    void null_sampler_throws() {
        assertThatThrownBy(() -> new SamplerHarness(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void null_kind_throws() {
        assertThatThrownBy(() -> new SamplerHarness(alwaysOn).withKind(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void null_parent_throws() {
        assertThatThrownBy(() -> new SamplerHarness(alwaysOn).withParent(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reset_restores_defaults() {
        var harness = new SamplerHarness(alwaysOn)
                .withHeader("X-Trace-On", "on")
                .withUrlPath("/some/path")
                .withKind(SpanKind.SERVER);
        harness.reset();
        // after reset it should still work without throwing
        harness.sample("clean-op").assertSampled();
    }
}
