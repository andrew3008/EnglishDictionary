package space.br1440.platform.tracing.runtime.sampling.policy;

import java.util.Map;
import java.util.Objects;

import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicySnapshot;

public final class ProductionSamplingPolicyChain {

    private static final String[] PRODUCTION_RULE_NAMES = {
            SamplingPolicyRuleNames.KILL_SWITCH,
            SamplingPolicyRuleNames.HARD_DROP,
            SamplingPolicyRuleNames.FORCE_HEADER,
            SamplingPolicyRuleNames.QA_TRACE,
            SamplingPolicyRuleNames.PARENT_DECISION,
            SamplingPolicyRuleNames.ROUTE_RATIO,
            SamplingPolicyRuleNames.DEFAULT_RATIO
    };

    private final KillSwitchPolicyRule killSwitchRule = new KillSwitchPolicyRule();
    private final HardDropPolicyRule hardDropRule = new HardDropPolicyRule();
    private final ForceHeaderPolicyRule forceHeaderRule = new ForceHeaderPolicyRule();
    private final QaTracePolicyRule qaTraceRule = new QaTracePolicyRule();
    private final ParentSampledPolicyRule parentSampledRule = new ParentSampledPolicyRule();
    private final RouteRatioPolicyRule routeRatioRule = new RouteRatioPolicyRule();
    private final DefaultRatioPolicyRule defaultRatioRule = new DefaultRatioPolicyRule();
    private final String[] ruleNames;

    private ProductionSamplingPolicyChain() {
        this.ruleNames = PRODUCTION_RULE_NAMES.clone();
    }

    public static ProductionSamplingPolicyChain production() {
        return new ProductionSamplingPolicyChain();
    }

    public SamplingPolicyDecision evaluate(SamplingPolicyRequest request, SamplingPolicySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
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
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(routeRatios, "routeRatios");
        Objects.requireNonNull(startupRules, "startupRules");

        SamplingPolicyDecision decision = killSwitchRule.evaluate(enabled);
        if (decision != null) {
            return decision;
        }

        decision = hardDropRule.evaluate(request, startupRules.getDroppedRoutes());
        if (decision != null) {
            return decision;
        }

        decision = forceHeaderRule.evaluate(request, startupRules.getForceRecordValues());
        if (decision != null) {
            return decision;
        }

        decision = qaTraceRule.evaluate(request);
        if (decision != null) {
            return decision;
        }

        decision = parentSampledRule.evaluate(request);
        if (decision != null) {
            return decision;
        }

        decision = routeRatioRule.evaluate(request, routeRatios);
        if (decision != null) {
            return decision;
        }

        return defaultRatioRule.evaluate(request, defaultRatio);
    }

    public int ruleCount() {
        return ruleNames.length;
    }

    public String ruleNameAt(int index) {
        return ruleNames[index];
    }
}
