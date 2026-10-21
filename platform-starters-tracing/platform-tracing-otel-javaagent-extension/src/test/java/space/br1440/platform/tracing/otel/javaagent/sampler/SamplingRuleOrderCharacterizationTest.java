package space.br1440.platform.tracing.otel.javaagent.sampler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;

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
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, java.util.Map.of(), 1.0);

        // when/then
        try (CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of(), List.of("on"))
        )) {
            assertThat(sampler.policyEngine().ruleCount()).isEqualTo(7);
            for (int i = 0; i < EXPECTED_RULE_NAMES.size(); i++) {
                assertThat(sampler.policyEngine().ruleNameAt(i))
                        .isEqualTo(EXPECTED_RULE_NAMES.get(i));
            }
        }
    }
}
