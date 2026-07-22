package space.br1440.platform.tracing.otel.javaagent.sampler;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Фиксирует порядок правил {@code SamplingPolicyEngine} после PR-6B delegation.
 */
class SamplingRuleOrderCharacterizationTest {

    private static final List<String> EXPECTED_RULE_NAMES = List.of(
            PlatformSamplingReasons.KILL_SWITCH,
            "hard_drop",
            PlatformSamplingReasons.FORCE_HEADER,
            PlatformSamplingReasons.QA_TRACE,
            "parent_decision",
            PlatformSamplingReasons.ROUTE_RATIO,
            "default_ratio"
    );

    @Test
    void policy_engine_order_matches_prior_composite_rule_chain() {
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of("on"), java.util.Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(holder);

        assertThat(sampler.policyEngine().ruleCount()).isEqualTo(7);
        for (int i = 0; i < EXPECTED_RULE_NAMES.size(); i++) {
            assertThat(sampler.policyEngine().ruleNameAt(i))
                    .isEqualTo(EXPECTED_RULE_NAMES.get(i));
        }
    }
}
