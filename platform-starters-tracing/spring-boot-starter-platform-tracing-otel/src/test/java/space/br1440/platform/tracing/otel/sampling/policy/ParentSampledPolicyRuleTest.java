package space.br1440.platform.tracing.otel.sampling.policy;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.sampling.model.ParentContextState;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecisionType;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ParentSampledPolicyRuleTest {

    private final ParentSampledPolicyRule rule = new ParentSampledPolicyRule();

    @Test
    void sampledParent_recordsAndSamples() {
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                null, null, null, false, ParentContextState.SAMPLED);

        SamplingPolicyDecision decision = rule.evaluate(request, null);

        assertThat(decision.decisionType()).isEqualTo(SamplingPolicyDecisionType.RECORD_AND_SAMPLE);
        assertThat(decision.reason()).isEqualTo(SamplingPolicyReason.PARENT_DECISION);
        assertThat(decision.winningRule()).isEqualTo("parent_decision");
    }

    @Test
    void notSampledParent_drops() {
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                null, null, null, false, ParentContextState.NOT_SAMPLED);

        SamplingPolicyDecision decision = rule.evaluate(request, null);

        assertThat(decision.decisionType()).isEqualTo(SamplingPolicyDecisionType.DROP);
        assertThat(decision.reason()).isEqualTo(SamplingPolicyReason.PARENT_DROP);
        assertThat(decision.winningRule()).isEqualTo("parent_decision");
    }

    @Test
    void absentParent_abstains() {
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                null, null, null, false, ParentContextState.ABSENT);

        assertThat(rule.evaluate(request, null)).isNull();
    }
}
