package space.br1440.platform.tracing.otel.sampling.policy;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.sampling.engine.SamplingPolicyEngine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Защита нормативного порядка production-цепочки и контракта «свежий массив на каждый вызов».
 */
class ProductionSamplingPolicyChainTest {

    @Test
    void productionRules_haveNormativeOrder() {
        ProductionSamplingPolicyChain chain = ProductionSamplingPolicyChain.production();

        assertThat(chain.ruleCount()).isEqualTo(7);
        assertThat(chain.ruleNameAt(0)).isEqualTo("kill_switch");
        assertThat(chain.ruleNameAt(1)).isEqualTo("hard_drop");
        assertThat(chain.ruleNameAt(2)).isEqualTo("force_header");
        assertThat(chain.ruleNameAt(3)).isEqualTo("qa_trace");
        assertThat(chain.ruleNameAt(4)).isEqualTo("parent_decision");
        assertThat(chain.ruleNameAt(5)).isEqualTo("route_ratio");
        assertThat(chain.ruleNameAt(6)).isEqualTo("default_ratio");
    }

    @Test
    void production_returnsIndependentChains() {
        ProductionSamplingPolicyChain first = ProductionSamplingPolicyChain.production();
        ProductionSamplingPolicyChain second = ProductionSamplingPolicyChain.production();

        // Production factory не разделяет mutable chain state между sampler instances.
        assertThat(first).isNotSameAs(second);
    }

    @Test
    void productionEngine_usesChainOrder() {
        SamplingPolicyEngine engine = SamplingPolicyEngine.productionEngine();

        assertThat(engine.ruleCount()).isEqualTo(7);
        assertThat(engine.ruleNameAt(0)).isEqualTo("kill_switch");
        assertThat(engine.ruleNameAt(6)).isEqualTo("default_ratio");
    }
}
