package space.br1440.platform.tracing.core.sampling.policy;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyDecisionType;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicySnapshot;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicySnapshotFixtures;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KillSwitchPolicyRuleTest {

    private final KillSwitchPolicyRule rule = new KillSwitchPolicyRule();

    @Test
    void disabled_returnsDropWithKillSwitchReason() {
        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFixtures.snapshot(false, List.of());
        SamplingPolicyDecision decision = rule.evaluate(new SamplingPolicyRequest("/api/x"), snapshot);

        assertThat(decision.decisionType()).isEqualTo(SamplingPolicyDecisionType.DROP);
        assertThat(decision.reason()).isEqualTo(SamplingPolicyReason.KILL_SWITCH);
        assertThat(decision.winningRule()).isEqualTo("kill_switch");
    }

    @Test
    void enabled_returnsNullForNextRule() {
        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFixtures.snapshot(true, List.of());
        assertThat(rule.evaluate(new SamplingPolicyRequest("/api/x"), snapshot)).isNull();
    }
}
