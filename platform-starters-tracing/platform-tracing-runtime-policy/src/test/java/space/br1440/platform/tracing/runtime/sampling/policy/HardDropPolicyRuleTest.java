package space.br1440.platform.tracing.runtime.sampling.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecisionType;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicySnapshot;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicySnapshotFixtures;

class HardDropPolicyRuleTest {

    private final HardDropPolicyRule rule = new HardDropPolicyRule();

    @Test
    void matchingPrefix_returnsDropWithDropPathReason() {
        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFixtures.snapshot(true, List.of("/actuator/health"));
        SamplingPolicyDecision decision = rule.evaluate(
                new SamplingPolicyRequest("/actuator/health/check"), snapshot.getDroppedRoutes());

        assertThat(decision.decisionType()).isEqualTo(SamplingPolicyDecisionType.DROP);
        assertThat(decision.reason()).isEqualTo(SamplingPolicyReason.HARD_DROP);
        assertThat(decision.reason().reasonCode()).isEqualTo(PlatformSamplingReasons.DROP_PATH);
        assertThat(decision.winningRule()).isEqualTo("hard_drop");
    }

    @Test
    void nonMatchingPrefix_returnsNull() {
        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFixtures.snapshot(true, List.of("/actuator/health"));
        assertThat(rule.evaluate(new SamplingPolicyRequest("/api/orders"), snapshot.getDroppedRoutes())).isNull();
    }

    @Test
    void emptyUrlPath_returnsNull() {
        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFixtures.snapshot(true, List.of("/actuator/health"));
        assertThat(rule.evaluate(new SamplingPolicyRequest(""), snapshot.getDroppedRoutes())).isNull();
        assertThat(rule.evaluate(new SamplingPolicyRequest(null), snapshot.getDroppedRoutes())).isNull();
    }

    @Test
    void emptyDropList_returnsNull() {
        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFixtures.snapshot(true, List.of());
        assertThat(rule.evaluate(new SamplingPolicyRequest("/actuator/health"), snapshot.getDroppedRoutes())).isNull();
    }
}
