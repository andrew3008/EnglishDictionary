package space.br1440.platform.tracing.runtime.sampling.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SamplingPolicySnapshotTest {

    @Test
    void normalizesAndCopiesDropPaths() {
        ArrayList<String> source = new ArrayList<>(List.of("  /a ", "", " /b"));
        source.add(1, null);
        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFixtures.snapshot(true, source);

        assertThat(snapshot.getDroppedRoutes()).containsExactly("/a", "/b");

        source.add("/mutated");
        assertThat(snapshot.getDroppedRoutes()).containsExactly("/a", "/b");
    }

    @Test
    void nullDropList_isEmpty() {
        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFixtures.snapshot(true, null);
        assertThat(snapshot.getDroppedRoutes()).isEmpty();
    }

    @Test
    void normalizesForceValues() {
        SamplingPolicySnapshot snapshot = new SamplingPolicySnapshot(
                true, List.of(), Set.of(" On ", "CUSTOM"), List.of(), 1.0);

        assertThat(snapshot.getForceRecordValues()).containsExactlyInAnyOrder("on", "custom");
        assertThat(snapshot.getDefaultRatio()).isEqualTo(1.0);
    }

    @Test
    void decision_requiresWinningRuleForDrop() {
        assertThatThrownBy(() -> SamplingPolicyDecision.drop(SamplingPolicyReason.KILL_SWITCH, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routeRatios_sortedLongestPrefixFirst() {
        SamplingPolicySnapshot snapshot = new SamplingPolicySnapshot(
                true, List.of(), Set.of(),
                List.of(
                        new RouteRatioPrefix("/api", 0.10),
                        new RouteRatioPrefix("/api/v2/orders", 1.00),
                        new RouteRatioPrefix("/api/v2", 0.50)),
                0.0);

        assertThat(snapshot.getRouteRatios())
                .extracting(RouteRatioPrefix::prefix)
                .containsExactly("/api/v2/orders", "/api/v2", "/api");
    }

    @Test
    void routeRatios_equalLength_lexicographicTieBreaker() {
        SamplingPolicySnapshot snapshot = new SamplingPolicySnapshot(
                true, List.of(), Set.of(),
                List.of(
                        new RouteRatioPrefix("/api/b", 0.20),
                        new RouteRatioPrefix("/api/a", 0.10)),
                0.0);

        assertThat(snapshot.getRouteRatios())
                .extracting(RouteRatioPrefix::prefix)
                .containsExactly("/api/a", "/api/b");
    }

    @Test
    void normalizeRouteRatios_doesNotMutateCallerList() {
        ArrayList<RouteRatioPrefix> source = new ArrayList<>(List.of(
                new RouteRatioPrefix("/a", 0.1),
                new RouteRatioPrefix("/a/b/c", 0.2)));

        new SamplingPolicySnapshot(true, List.of(), Set.of(), source, 0.0);

        assertThat(source)
                .extracting(RouteRatioPrefix::prefix)
                .containsExactly("/a", "/a/b/c");
    }
}
