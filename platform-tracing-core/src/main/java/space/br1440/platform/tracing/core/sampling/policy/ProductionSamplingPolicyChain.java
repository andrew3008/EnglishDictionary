package space.br1440.platform.tracing.core.sampling.policy;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class ProductionSamplingPolicyChain {

    public static SamplingPolicyRule[] productionRules() {
        return new SamplingPolicyRule[] {
                new KillSwitchPolicyRule(),
                new HardDropPolicyRule(),
                new ForceHeaderPolicyRule(),
                new QaTracePolicyRule(),
                new ParentSampledPolicyRule(),
                new RouteRatioPolicyRule(),
                new DefaultRatioPolicyRule()
        };
    }

    public static SamplingPolicyRule[] foundationRules() {
        return new SamplingPolicyRule[] {
                new KillSwitchPolicyRule(),
                new HardDropPolicyRule()
        };
    }
}
