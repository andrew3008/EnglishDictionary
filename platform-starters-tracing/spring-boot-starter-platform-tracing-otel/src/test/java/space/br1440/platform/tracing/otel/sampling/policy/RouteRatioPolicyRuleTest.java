package space.br1440.platform.tracing.otel.sampling.policy;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.sampling.model.ParentContextState;
import space.br1440.platform.tracing.otel.sampling.model.RouteRatioPrefix;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecisionType;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshot;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RouteRatioPolicyRuleTest {

    private final RouteRatioPolicyRule rule = new RouteRatioPolicyRule();

    @Test
    void routeRatioOne_samples() {
        SamplingPolicySnapshot snapshot = new SamplingPolicySnapshot(
                true,
                List.of(),
                Set.of(),
                List.of(new RouteRatioPrefix("/api/v1/critical", 1.0)), 0.0);
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                "/api/v1/critical/checkout", "00000000000000000000000000000000", null, false, ParentContextState.ABSENT);

        SamplingPolicyDecision decision = rule.evaluate(request, snapshot);

        assertThat(decision.decisionType()).isEqualTo(SamplingPolicyDecisionType.RECORD_AND_SAMPLE);
        assertThat(decision.reason()).isEqualTo(SamplingPolicyReason.ROUTE_RATIO);
    }

    @Test
    void routeRatioZero_drops() {
        SamplingPolicySnapshot snapshot = new SamplingPolicySnapshot(
                true,
                List.of(),
                Set.of(),
                List.of(new RouteRatioPrefix("/api/v1/noisy", 0.0)), 1.0);
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                "/api/v1/noisy/search", "00000000000000000000000000000000", null, false, ParentContextState.ABSENT);

        SamplingPolicyDecision decision = rule.evaluate(request, snapshot);

        assertThat(decision.decisionType()).isEqualTo(SamplingPolicyDecisionType.DROP);
        assertThat(decision.reason()).isEqualTo(SamplingPolicyReason.ROUTE_RATIO_DROP);
    }

    @Test
    void noMatchingPrefix_abstains() {
        SamplingPolicySnapshot snapshot = new SamplingPolicySnapshot(
                true,
                List.of(),
                Set.of(),
                List.of(new RouteRatioPrefix("/api/v1/noisy", 0.0)), 1.0);
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                "/api/v1/orders", "00000000000000000000000000000000", null, false, ParentContextState.ABSENT);

        assertThat(rule.evaluate(request, snapshot)).isNull();
    }

    @Test
    void missingUrlPath_abstains() {
        SamplingPolicySnapshot snapshot = new SamplingPolicySnapshot(
                true,
                List.of(),
                Set.of(),
                List.of(new RouteRatioPrefix("/api", 1.0)), 0.0);

        assertThat(rule.evaluate(new SamplingPolicyRequest(null), snapshot)).isNull();
    }

    // -- PR-9G: longest-prefix-wins for overlapping route prefixes (Opus blocker B2) -------------

    @Test
    void overlappingPrefixes_mostSpecificWins() {
        // /api=0.10, /api/v2=0.50, /api/v2/orders=1.00 → /api/v2/orders/123 must use 1.00 (SAMPLE).
        SamplingPolicySnapshot snapshot = new SamplingPolicySnapshot(
                true,
                List.of(),
                Set.of(),
                List.of(
                        new RouteRatioPrefix("/api", 0.10),
                        new RouteRatioPrefix("/api/v2", 0.50),
                        new RouteRatioPrefix("/api/v2/orders", 1.00)), 0.0);
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                "/api/v2/orders/123", "00000000000000000000000000000000", null, false, ParentContextState.ABSENT);

        SamplingPolicyDecision decision = rule.evaluate(request, snapshot);

        assertThat(decision.decisionType()).isEqualTo(SamplingPolicyDecisionType.RECORD_AND_SAMPLE);
        assertThat(decision.reason()).isEqualTo(SamplingPolicyReason.ROUTE_RATIO);
    }

    @Test
    void lessSpecificFallback_whenDeeperPrefixDoesNotMatch() {
        // /api=0.10 (drop via 0.0 here), /api/v2=0.50 → /api/v3/users matches only /api.
        SamplingPolicySnapshot snapshot = new SamplingPolicySnapshot(
                true,
                List.of(),
                Set.of(),
                List.of(
                        new RouteRatioPrefix("/api", 0.0),
                        new RouteRatioPrefix("/api/v2", 1.0)), 1.0);
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                "/api/v3/users", "00000000000000000000000000000000", null, false, ParentContextState.ABSENT);

        SamplingPolicyDecision decision = rule.evaluate(request, snapshot);

        // /api wins (ratio 0.0 → DROP), proving the deeper /api/v2 was not erroneously selected.
        assertThat(decision.decisionType()).isEqualTo(SamplingPolicyDecisionType.DROP);
        assertThat(decision.reason()).isEqualTo(SamplingPolicyReason.ROUTE_RATIO_DROP);
    }

    @Test
    void overlappingPrefixes_insertionOrderIndependent() {
        List<RouteRatioPrefix> ascending = List.of(
                new RouteRatioPrefix("/api", 0.0),
                new RouteRatioPrefix("/api/v2", 0.0),
                new RouteRatioPrefix("/api/v2/orders", 1.0));
        List<RouteRatioPrefix> descending = List.of(
                new RouteRatioPrefix("/api/v2/orders", 1.0),
                new RouteRatioPrefix("/api/v2", 0.0),
                new RouteRatioPrefix("/api", 0.0));
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                "/api/v2/orders/123", "00000000000000000000000000000000", null, false, ParentContextState.ABSENT);

        for (List<RouteRatioPrefix> order : List.of(ascending, descending)) {
            SamplingPolicySnapshot snapshot = new SamplingPolicySnapshot(
                    true, List.of(), Set.of(), order, 0.0);
            SamplingPolicyDecision decision = rule.evaluate(request, snapshot);
            assertThat(decision.decisionType()).isEqualTo(SamplingPolicyDecisionType.RECORD_AND_SAMPLE);
        }
    }
}
