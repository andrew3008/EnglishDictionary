package space.br1440.platform.tracing.runtime.sampling.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.runtime.sampling.model.ParentContextState;
import space.br1440.platform.tracing.runtime.sampling.model.RouteRatioPrefix;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecisionType;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicySnapshot;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicySnapshotFixtures;

class SamplingPolicyEngineTest {

    @Test
    void productionEngine_matchesCompositeSamplerRuleOrder() {
        SamplingPolicyEngine engine = SamplingPolicyEngine.productionEngine();
        assertThat(engine.ruleCount()).isEqualTo(7);
        assertThat(engine.ruleNameAt(0)).isEqualTo("kill_switch");
        assertThat(engine.ruleNameAt(1)).isEqualTo("hard_drop");
        assertThat(engine.ruleNameAt(2)).isEqualTo("force_header");
        assertThat(engine.ruleNameAt(3)).isEqualTo("qa_trace");
        assertThat(engine.ruleNameAt(4)).isEqualTo("parent_decision");
        assertThat(engine.ruleNameAt(5)).isEqualTo("route_ratio");
        assertThat(engine.ruleNameAt(6)).isEqualTo("default_ratio");
    }

    @Test
    void killSwitchWinsOverMatchingHardDrop() {
        SamplingPolicyEngine engine = SamplingPolicyEngine.productionEngine();
        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFixtures.snapshot(
                false,
                List.of("/actuator/health")
        );

        SamplingPolicyDecision decision = engine.evaluate(
                new SamplingPolicyRequest("/actuator/health"),
                snapshot
        );

        assertThat(decision.reason()).isEqualTo(SamplingPolicyReason.KILL_SWITCH);
    }

    @Test
    void killSwitchWinsOverMatchingRouteRatio() {
        SamplingPolicyEngine engine = SamplingPolicyEngine.productionEngine();
        SamplingPolicySnapshot snapshot = new SamplingPolicySnapshot(
                false,
                List.of(),
                Set.of(),
                List.of(new RouteRatioPrefix("/api", 1.0)),
                1.0);
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                "/api/orders", "00000000000000000000000000000000", null, false,
                ParentContextState.ABSENT);

        SamplingPolicyDecision decision = engine.evaluate(request, snapshot);

        assertThat(decision.decisionType()).isEqualTo(SamplingPolicyDecisionType.DROP);
        assertThat(decision.reason()).isEqualTo(SamplingPolicyReason.KILL_SWITCH);
    }

    @Test
    void forceHeaderWinsOverDefaultRatioZero() {
        SamplingPolicyEngine engine = SamplingPolicyEngine.productionEngine();
        SamplingPolicySnapshot snapshot = new SamplingPolicySnapshot(
                true, List.of(), Set.of("on"), List.of(), 0.0);
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                null, null, "on", false, ParentContextState.ABSENT);

        SamplingPolicyDecision decision = engine.evaluate(request, snapshot);

        assertThat(decision.reason()).isEqualTo(SamplingPolicyReason.FORCE_HEADER);
    }

    @Test
    void parentNotSampledWinsOverRouteRatioOne() {
        SamplingPolicyEngine engine = SamplingPolicyEngine.productionEngine();
        SamplingPolicySnapshot snapshot = new SamplingPolicySnapshot(
                true,
                List.of(),
                Set.of("on"),
                List.of(new RouteRatioPrefix("/api/v1/critical", 1.0)), 1.0);
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                "/api/v1/critical/checkout",
                "00000000000000000000000000000000",
                null,
                false,
                ParentContextState.NOT_SAMPLED);

        SamplingPolicyDecision decision = engine.evaluate(request, snapshot);

        assertThat(decision.reason()).isEqualTo(SamplingPolicyReason.PARENT_DROP);
    }

    @Test
    void productionEngine_neverAbstains() {
        SamplingPolicyEngine engine = SamplingPolicyEngine.productionEngine();
        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFixtures.snapshot(true, List.of());

        SamplingPolicyDecision decision = engine.evaluate(new SamplingPolicyRequest(null), snapshot);

        assertThat(decision.decisionType()).isNotEqualTo(SamplingPolicyDecisionType.ABSTAIN);
        assertThat(decision.reason()).isEqualTo(SamplingPolicyReason.DEFAULT_RATIO);
    }
}
