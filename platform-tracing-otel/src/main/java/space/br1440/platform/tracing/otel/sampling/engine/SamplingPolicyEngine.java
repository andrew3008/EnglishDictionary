package space.br1440.platform.tracing.otel.sampling.engine;

import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshot;
import space.br1440.platform.tracing.otel.sampling.policy.ProductionSamplingPolicyChain;

import java.util.Map;

public final class SamplingPolicyEngine {

    private final ProductionSamplingPolicyChain policyChain;

    private SamplingPolicyEngine(ProductionSamplingPolicyChain policyChain) {
        this.policyChain = policyChain;
    }

    static SamplingPolicyEngine foundationEngine() {
        return new SamplingPolicyEngine(ProductionSamplingPolicyChain.foundation());
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
                snapshot);
    }

    /**
     * Оценивает Category A runtime policy вместе с отдельными Category B startup rules.
     */
    public SamplingPolicyDecision evaluate(
            SamplingPolicyRequest request,
            boolean enabled,
            Map<String, Double> routeRatios,
            double defaultRatio,
            SamplingPolicySnapshot startupRules) {
        return policyChain.evaluate(
                request,
                enabled,
                routeRatios,
                defaultRatio,
                startupRules);
    }

    public int ruleCount() {
        return policyChain.ruleCount();
    }

    public String ruleNameAt(int index) {
        return policyChain.ruleNameAt(index);
    }
}
