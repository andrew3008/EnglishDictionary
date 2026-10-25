package space.br1440.platform.tracing.runtime.sampling.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicySnapshot;
import space.br1440.platform.tracing.runtime.sampling.properties.SamplingPolicySnapshotFactory;

class SamplingPolicySnapshotFactoryTest {

    @Test
    void createsProductionCategoryBOverrides() {
        // given
        List<String> droppedRoutes = List.of("  /health ", "/ready");
        List<String> forceRecordValues = List.of(" On ", "CUSTOM");

        // when
        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFactory.create(
                droppedRoutes,
                forceRecordValues
        );

        // then
        assertThat(snapshot.isEnabled()).isTrue();
        assertThat(snapshot.getDefaultRatio()).isEqualTo(1.0d);
        assertThat(snapshot.getRouteRatios()).isEmpty();
        assertThat(snapshot.getDroppedRoutes()).containsExactly("/health", "/ready");
        assertThat(snapshot.getForceRecordValues()).containsExactlyInAnyOrder("on", "custom");
    }

    @Test
    void rejectsInvalidProductionInput() {
        // given
        List<String> droppedRoutes = List.of("relative");

        // when
        // then
        assertThatThrownBy(() -> SamplingPolicySnapshotFactory.create(droppedRoutes, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Drop path must start with /");
    }
}
