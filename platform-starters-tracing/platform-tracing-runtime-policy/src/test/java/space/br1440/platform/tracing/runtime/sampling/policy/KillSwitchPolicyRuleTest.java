package space.br1440.platform.tracing.runtime.sampling.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecisionType;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyReason;

class KillSwitchPolicyRuleTest {

    private final KillSwitchPolicyRule rule = new KillSwitchPolicyRule();

    @Test
    void disabled_returnsDropWithKillSwitchReason() {
        SamplingPolicyDecision decision = rule.evaluate(false);

        assertThat(decision.decisionType()).isEqualTo(SamplingPolicyDecisionType.DROP);
        assertThat(decision.reason()).isEqualTo(SamplingPolicyReason.KILL_SWITCH);
        assertThat(decision.winningRule()).isEqualTo("kill_switch");
    }

    @Test
    void enabled_returnsNullForNextRule() {
        assertThat(rule.evaluate(true)).isNull();
    }
}
