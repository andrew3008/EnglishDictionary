package space.br1440.platform.tracing.core.sampling.engine;

import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicySnapshot;
import space.br1440.platform.tracing.core.sampling.policy.ProductionSamplingPolicyChain;
import space.br1440.platform.tracing.core.sampling.policy.SamplingPolicyRule;

public final class SamplingPolicyEngine {

    private final SamplingPolicyRule[] rules;

    SamplingPolicyEngine(SamplingPolicyRule... rules) {
        if (rules == null || rules.length == 0) {
            throw new IllegalArgumentException("rules must not be empty");
        }

        this.rules = rules.clone();
    }

    static SamplingPolicyEngine foundationEngine() {
        return new SamplingPolicyEngine(ProductionSamplingPolicyChain.foundationRules());
    }

    public static SamplingPolicyEngine productionEngine() {
        return new SamplingPolicyEngine(ProductionSamplingPolicyChain.productionRules());
    }

    public SamplingPolicyDecision evaluate(SamplingPolicyRequest request, SamplingPolicySnapshot snapshot) {
        for (SamplingPolicyRule rule : rules) {
            SamplingPolicyDecision decision = rule.evaluate(request, snapshot);
            if (decision != null) {
                return decision;
            }
        }

        return SamplingPolicyDecision.abstain();
    }

    public int ruleCount() {
        return rules.length;
    }

    public String ruleNameAt(int index) {
        return rules[index].ruleName();
    }
}
