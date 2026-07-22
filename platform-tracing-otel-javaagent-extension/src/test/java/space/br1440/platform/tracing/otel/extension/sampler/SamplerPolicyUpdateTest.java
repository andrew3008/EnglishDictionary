package space.br1440.platform.tracing.otel.extension.sampler;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SamplerPolicyUpdateTest {

    private static SamplerState base() {
        return new SamplerState(true, List.of(), Set.of("on"), Map.of(), 0.5, 1, Instant.now(), "startup");
    }

    @Test
    void buildNext_incrementsVersionAndRecordsSource() {
        SamplerState next = SamplerPolicyUpdate.buildNext(
                base(),
                false,
                0.25,
                new String[]{"/metrics"},
                new String[]{"on", "yes"},
                new String[]{"/api"},
                new double[]{0.1},
                "ops-console");

        assertThat(next.version()).isEqualTo(2);
        assertThat(next.source()).isEqualTo("ops-console");
        assertThat(next.enabled()).isFalse();
        assertThat(next.defaultRatio()).isEqualTo(0.25);
        assertThat(next.droppedRoutes()).containsExactly("/metrics");
        assertThat(next.forceRecordValues()).containsExactlyInAnyOrder("on", "yes");
        assertThat(next.routeRatios()).containsEntry("/api", 0.1);
    }

    @Test
    void validateDomain_rejectsMismatchedRouteArrays() {
        assertThatThrownBy(() -> SamplerPolicyUpdate.validateDomain(
                0.5, null, null, new String[]{"/a"}, new double[]{0.1, 0.2}))
                .hasMessageContaining("length");
    }

    @Test
    void validateDomain_rejectsNullForceValue() {
        assertThatThrownBy(() -> SamplerPolicyUpdate.validateDomain(
                0.5, null, new String[]{null}, null, null))
                .hasMessageContaining("Force record value");
    }

    @Test
    void validateDomain_rejectsNullDropPath() {
        assertThatThrownBy(() -> SamplerPolicyUpdate.validateDomain(
                0.5, new String[]{null}, null, null, null))
                .hasMessageContaining("Drop path");
    }

    @Test
    void validateDomain_rejectsOutOfRangeDefaultRatio() {
        assertThatThrownBy(() -> SamplerPolicyUpdate.validateDomain(
                1.5, null, null, null, null))
                .hasMessageContaining("defaultRatio");
        assertThatThrownBy(() -> SamplerPolicyUpdate.validateDomain(
                -0.1, null, null, null, null))
                .hasMessageContaining("defaultRatio");
    }

    @Test
    void validateDomain_rejectsOutOfRangeRouteRatio() {
        assertThatThrownBy(() -> SamplerPolicyUpdate.validateDomain(
                0.5, null, null, new String[]{"/api"}, new double[]{1.5}))
                .hasMessageContaining("Route ratio");
    }

    @Test
    void validateDomain_rejectsBlankRoutePrefix() {
        assertThatThrownBy(() -> SamplerPolicyUpdate.validateDomain(
                0.5, null, null, new String[]{"   "}, new double[]{0.5}))
                .hasMessageContaining("Route ratio prefix");
    }

    @Test
    void validateDomain_acceptsValidBoundaryInput() {
        SamplerPolicyUpdate.validateDomain(
                0.0, new String[]{"/metrics"}, new String[]{"on"},
                new String[]{"/api"}, new double[]{1.0});
        SamplerPolicyUpdate.validateDomain(
                1.0, null, null, null, null);
    }

    @Test
    void normalizeSource_defaultsBlankToJmx() {
        assertThat(SamplerPolicyUpdate.normalizeSource(null)).isEqualTo("JMX");
        assertThat(SamplerPolicyUpdate.normalizeSource("  ")).isEqualTo("JMX");
    }
}
