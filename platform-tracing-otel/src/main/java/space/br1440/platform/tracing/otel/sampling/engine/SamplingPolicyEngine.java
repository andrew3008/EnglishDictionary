package space.br1440.platform.tracing.otel.sampling.engine;

import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshot;
import space.br1440.platform.tracing.otel.sampling.policy.ProductionSamplingPolicyChain;

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
        return policyChain.evaluate(request, snapshot);
    }

    public int ruleCount() {
        return policyChain.ruleCount();
    }

    public String ruleNameAt(int index) {
        return policyChain.ruleNameAt(index);
    }
}
