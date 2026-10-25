package space.br1440.platform.tracing.runtime.sampling.engine;

import java.util.Map;

import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicySnapshot;
import space.br1440.platform.tracing.runtime.sampling.policy.ProductionSamplingPolicyChain;

public final class SamplingPolicyEngine {

    private final ProductionSamplingPolicyChain policyChain;

    private SamplingPolicyEngine(ProductionSamplingPolicyChain policyChain) {
        this.policyChain = policyChain;
    }

    public static SamplingPolicyEngine productionEngine() {
        return new SamplingPolicyEngine(ProductionSamplingPolicyChain.production());
    }

    public SamplingPolicyDecision evaluate(SamplingPolicyRequest request, SamplingPolicySnapshot snapshot) {
        return evaluate(
                request,
                snapshot.isEnabled(),
                snapshot.getRouteRatioMap(),
                snapshot.getDefaultRatio(),
                snapshot
        );
    }

    public SamplingPolicyDecision evaluate(SamplingPolicyRequest request,
                                           boolean enabled,
                                           Map<String, Double> routeRatios,
                                           double defaultRatio,
                                           SamplingPolicySnapshot startupRules) {
        return policyChain.evaluate(
                request,
                enabled,
                routeRatios,
                defaultRatio,
                startupRules
        );
    }

    public int ruleCount() {
        return policyChain.ruleCount();
    }

    public String ruleNameAt(int index) {
        return policyChain.ruleNameAt(index);
    }
}
