package space.br1440.platform.tracing.test.junit;

import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import space.br1440.platform.tracing.test.harness.SamplerHarness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SamplerHarnessExtension}:
 * lifecycle (fresh harness per test), parameter injection, {@code @Nested}, null-guard.
 */
class SamplerHarnessExtensionTest {

    @RegisterExtension
    static final SamplerHarnessExtension ext =
            SamplerHarnessExtension.of(Sampler::alwaysOn);

    // -------------------------------------------------------------------------
    // Parameter injection
    // -------------------------------------------------------------------------

    @Test
    void parameter_injection_provides_non_null_harness(SamplerHarness h) {
        assertThat(h).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Harness is usable
    // -------------------------------------------------------------------------

    @Test
    void harness_samples_with_alwaysOn(SamplerHarness h) {
        assertThat(h.sample().getDecision())
                .isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
    }

    @Test
    void each_test_gets_fresh_harness_with_empty_attributes(SamplerHarness h) {
        // no attributes added by anyone else — harness is always fresh
        assertThat(h.attributesSnapshot().asMap()).isEmpty();
    }

    @Test
    void attributes_added_in_one_test_do_not_leak(SamplerHarness h) {
        // add attribute in this test
        h.putStringAttribute("http.method", "GET");
        assertThat(h.attributesSnapshot().asMap()).hasSize(1);
        // next test will get a fresh harness (verified by the test above)
    }

    @Test
    void second_test_harness_has_no_leaked_attributes(SamplerHarness h) {
        assertThat(h.attributesSnapshot().asMap()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // @Nested injection
    // -------------------------------------------------------------------------

    @Nested
    class NestedTests {

        @Test
        void nested_injection_works(SamplerHarness h) {
            assertThat(h).isNotNull();
            assertThat(h.sample().getDecision())
                    .isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        }

        @Test
        void nested_harness_is_fresh(SamplerHarness h) {
            assertThat(h.attributesSnapshot().asMap()).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // Null-guard on factory
    // -------------------------------------------------------------------------

    @Test
    void null_factory_throws() {
        assertThatThrownBy(() -> SamplerHarnessExtension.of(null))
                .isInstanceOf(NullPointerException.class);
    }

    // -------------------------------------------------------------------------
    // alwaysOff variant — separate extension instance in test
    // -------------------------------------------------------------------------

    @Test
    void alwaysOff_extension_can_be_created() {
        SamplerHarnessExtension offExt = SamplerHarnessExtension.of(Sampler::alwaysOff);
        assertThat(offExt).isNotNull();
    }
}
