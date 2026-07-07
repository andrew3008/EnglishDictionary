package space.br1440.platform.tracing.core.sampling.policy;

import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicySnapshot;

final class KillSwitchPolicyRule implements SamplingPolicyRule {

    private static final SamplingPolicyDecision DROP = SamplingPolicyDecision.drop(
            SamplingPolicyReason.KILL_SWITCH, SamplingPolicyRuleNames.KILL_SWITCH
    );

    @Override
    public String ruleName() {
        return SamplingPolicyRuleNames.KILL_SWITCH;
    }

    @Override
    public SamplingPolicyDecision evaluate(SamplingPolicyRequest request, SamplingPolicySnapshot snapshot) {
        if (!snapshot.isEnabled()) {
            return DROP;
        }

        return null;
    }
}
