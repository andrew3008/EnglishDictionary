package space.br1440.platform.tracing.otel.javaagent.jmx.sampling;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Read-only contract tests for {@link PlatformSamplingControl}.
 */
class PlatformSamplingControlTest {

    @Test
    void getSamplingRatio_возвращает_текущее_значение_из_accepted_policy() {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.42d);
        PlatformSamplingControl control = new PlatformSamplingControl(holder, new CompositeSampler(holder));

        assertThat(control.getSamplingRatio()).isEqualTo(0.42d);
    }

    @Test
    void isSamplerEnabled_отражает_accepted_policy() {
        AcceptedPolicyAccess enabled = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.5d);
        AcceptedPolicyAccess disabled = AcceptedPolicyAccess.isolatedSampling(
                false, List.of(), List.of(), Map.of(), 0.5d);

        assertThat(new PlatformSamplingControl(enabled, new CompositeSampler(enabled)).isSamplerEnabled())
                .isTrue();
        assertThat(new PlatformSamplingControl(disabled, new CompositeSampler(disabled)).isSamplerEnabled())
                .isFalse();
    }

    @Test
    void getRouteRatios_отражает_accepted_policy() {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of("/api", 0.2d), 0.5d);
        PlatformSamplingControl control = new PlatformSamplingControl(holder, new CompositeSampler(holder));

        assertThat(control.getRouteRatios()).containsEntry("/api", 0.2d);
    }

    @Test
    void nullConfigHolder_геттеры_возвращают_дефолты() {
        PlatformSamplingControl control = new PlatformSamplingControl(null, null);

        assertThat(control.getSamplingRatio()).isEqualTo(-1.0);
        assertThat(control.isSamplerEnabled()).isFalse();
        assertThat(control.getRouteRatios()).isEmpty();
        assertThat(control.getSamplingConfigVersion()).isEqualTo(-1);
    }

    @Test
    void getSamplingConfigLastUpdatedSource_возвращает_accepted_state() {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.5d);
        PlatformSamplingControl control = new PlatformSamplingControl(holder, new CompositeSampler(holder));

        assertThat(control.getSamplingConfigLastUpdatedSource()).isEqualTo("accepted-state");
    }
}
