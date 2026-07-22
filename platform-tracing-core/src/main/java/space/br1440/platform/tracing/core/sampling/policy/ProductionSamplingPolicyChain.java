package space.br1440.platform.tracing.core.sampling.policy;

import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicySnapshot;

/** Фиксированная platform-owned цепочка sampling rules без внешней точки расширения. */
public final class ProductionSamplingPolicyChain {

    private final SamplingPolicyRule[] rules;

    private ProductionSamplingPolicyChain(SamplingPolicyRule[] rules) {
        this.rules = rules;
    }

    public static ProductionSamplingPolicyChain production() {
        return new ProductionSamplingPolicyChain(new SamplingPolicyRule[] {
                new KillSwitchPolicyRule(),
                new HardDropPolicyRule(),
                new ForceHeaderPolicyRule(),
                new QaTracePolicyRule(),
                new ParentSampledPolicyRule(),
                new RouteRatioPolicyRule(),
                new DefaultRatioPolicyRule()
        });
    }

    public static ProductionSamplingPolicyChain foundation() {
        return new ProductionSamplingPolicyChain(new SamplingPolicyRule[] {
                new KillSwitchPolicyRule(),
                new HardDropPolicyRule()
        });
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
