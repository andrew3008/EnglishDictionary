package space.br1440.platform.tracing.test.junit;

import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import space.br1440.platform.tracing.test.harness.SamplerHarness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SamplerHarnessExtension}: lifecycle (reset between tests)
 * and {@link org.junit.jupiter.api.extension.ParameterResolver} injection.
 */
class SamplerHarnessExtensionTest {

    @RegisterExtension
    static final SamplerHarnessExtension harness =
            SamplerHarnessExtension.of(Sampler::alwaysOn);

    @Test
    void parameter_injection_works(SamplerHarness h) {
        assertThat(h).isNotNull();
        h.sample("op").assertSampled();
    }

    @Test
    void second_test_gets_fresh_harness(SamplerHarness h) {
        // harness.reset() is called in beforeEach; state from previous test must be gone
        h.withUrlPath("/any").sample("op").assertSampled();
    }

    @Nested
    class NestedTests {
        @Test
        void nested_parameter_injection_works(SamplerHarness h) {
            assertThat(h).isNotNull();
            h.sample("nested-op").assertSampled();
        }
    }

    @Test
    void null_factory_throws() {
        assertThatThrownBy(() -> SamplerHarnessExtension.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
