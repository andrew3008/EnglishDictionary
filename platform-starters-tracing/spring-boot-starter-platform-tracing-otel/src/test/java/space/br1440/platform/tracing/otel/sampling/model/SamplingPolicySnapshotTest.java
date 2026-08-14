package space.br1440.platform.tracing.otel.sampling.model;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.runtime.versioned.VersionedState;
import space.br1440.platform.tracing.otel.sampling.properties.SamplingPolicyProperties;
import space.br1440.platform.tracing.otel.sampling.properties.SamplingPolicySnapshotFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SamplingPolicySnapshotTest {

    @Test
    void doesNotImplementVersionedMarker() {
        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFixtures.snapshot(true, List.of());
        assertThat(snapshot).isNotInstanceOf(VersionedState.class);
    }

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
    void factory_copiesRouteRatios() {
        var source = new java.util.LinkedHashMap<String, Double>();
        source.put("/api", 0.5);
        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFactory.create(
                new SamplingPolicyProperties(true, 1.0, List.of(), Set.of(), source));

        assertThat(snapshot.getRouteRatios()).hasSize(1);
        assertThat(snapshot.getRouteRatios()[0].prefix()).isEqualTo("/api");
        source.put("/mutated", 0.1);
        assertThat(snapshot.getRouteRatios()).hasSize(1);
    }

    @Test
    void decision_requiresWinningRuleForDrop() {
        assertThatThrownBy(() -> SamplingPolicyDecision.drop(SamplingPolicyReason.KILL_SWITCH, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -- PR-9G: deterministic route-ratio ordering (Opus blocker B2) -----------------------------

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
    void routeRatios_insertionOrderIndependent_viaFactory() {
        var ascending = new java.util.LinkedHashMap<String, Double>();
        ascending.put("/api", 0.10);
        ascending.put("/api/v2", 0.50);
        ascending.put("/api/v2/orders", 1.00);

        var descending = new java.util.LinkedHashMap<String, Double>();
        descending.put("/api/v2/orders", 1.00);
        descending.put("/api/v2", 0.50);
        descending.put("/api", 0.10);

        var hashOrder = new java.util.HashMap<String, Double>();
        hashOrder.put("/api/v2", 0.50);
        hashOrder.put("/api", 0.10);
        hashOrder.put("/api/v2/orders", 1.00);

        String[] expected = {"/api/v2/orders", "/api/v2", "/api"};
        for (java.util.Map<String, Double> input : List.of(ascending, descending, hashOrder)) {
            SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFactory.create(
                    new SamplingPolicyProperties(true, 0.0, List.of(), Set.of(), input));
            assertThat(snapshot.getRouteRatios())
                    .extracting(RouteRatioPrefix::prefix)
                    .containsExactly(expected);
        }
    }

    @Test
    void normalizeRouteRatios_doesNotMutateCallerList() {
        java.util.ArrayList<RouteRatioPrefix> source = new java.util.ArrayList<>(List.of(
                new RouteRatioPrefix("/a", 0.1),
                new RouteRatioPrefix("/a/b/c", 0.2)));

        new SamplingPolicySnapshot(true, List.of(), Set.of(), source, 0.0);

        assertThat(source)
                .extracting(RouteRatioPrefix::prefix)
                .containsExactly("/a", "/a/b/c");
    }
}
