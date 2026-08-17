package space.br1440.platform.tracing.runtime.sampling.policy;

import java.util.List;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyRequest;

final class HardDropPolicyRule {

    private static final SamplingPolicyDecision DROP = SamplingPolicyDecision.drop(
            SamplingPolicyReason.HARD_DROP, SamplingPolicyRuleNames.HARD_DROP
    );

    SamplingPolicyDecision evaluate(SamplingPolicyRequest request, List<String> droppedRoutes) {
        if (droppedRoutes.isEmpty()) {
            return null;
        }

        String urlPath = request.urlPath();
        if (StringUtils.isNullOrEmpty(urlPath)) {
            return null;
        }

        for (String prefix : droppedRoutes) {
            if (urlPath.startsWith(prefix)) {
                return DROP;
            }
        }

        return null;
    }
}
