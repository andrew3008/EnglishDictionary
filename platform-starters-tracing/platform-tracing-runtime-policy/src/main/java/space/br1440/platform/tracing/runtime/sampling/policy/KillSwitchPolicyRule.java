package space.br1440.platform.tracing.runtime.sampling.policy;

import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyReason;

final class KillSwitchPolicyRule {

    private static final SamplingPolicyDecision DROP = SamplingPolicyDecision.drop(
            SamplingPolicyReason.KILL_SWITCH, SamplingPolicyRuleNames.KILL_SWITCH
    );

    SamplingPolicyDecision evaluate(boolean enabled) {
        if (!enabled) {
            return DROP;
        }

        return null;
    }
}
