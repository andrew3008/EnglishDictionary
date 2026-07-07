package space.br1440.platform.tracing.core.sampling.policy;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.core.sampling.engine.SamplingPolicyEngine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Защита нормативного порядка production-цепочки и контракта «свежий массив на каждый вызов».
 */
class ProductionSamplingPolicyChainTest {

    @Test
    void productionRules_haveNormativeOrder() {
        SamplingPolicyRule[] rules = ProductionSamplingPolicyChain.productionRules();

        assertThat(rules).hasSize(7);
        assertThat(rules[0].ruleName()).isEqualTo("kill_switch");
        assertThat(rules[1].ruleName()).isEqualTo("hard_drop");
        assertThat(rules[2].ruleName()).isEqualTo("force_header");
        assertThat(rules[3].ruleName()).isEqualTo("qa_trace");
        assertThat(rules[4].ruleName()).isEqualTo("parent_decision");
        assertThat(rules[5].ruleName()).isEqualTo("route_ratio");
        assertThat(rules[6].ruleName()).isEqualTo("default_ratio");
    }

    @Test
    void productionRules_returnsFreshArrayEachCall() {
        SamplingPolicyRule[] first = ProductionSamplingPolicyChain.productionRules();
        SamplingPolicyRule[] second = ProductionSamplingPolicyChain.productionRules();

        // Разные массивы и разные экземпляры правил — никакого разделяемого состояния.
        assertThat(first).isNotSameAs(second);
        assertThat(first[0]).isNotSameAs(second[0]);
    }

    @Test
    void productionEngine_usesChainOrder() {
        SamplingPolicyEngine engine = SamplingPolicyEngine.productionEngine();

        assertThat(engine.ruleCount()).isEqualTo(7);
        assertThat(engine.ruleNameAt(0)).isEqualTo("kill_switch");
        assertThat(engine.ruleNameAt(6)).isEqualTo("default_ratio");
    }
}
