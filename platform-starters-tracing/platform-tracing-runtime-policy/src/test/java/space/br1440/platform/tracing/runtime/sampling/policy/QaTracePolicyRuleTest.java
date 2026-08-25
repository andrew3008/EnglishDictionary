package space.br1440.platform.tracing.runtime.sampling.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.runtime.sampling.model.ParentContextState;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyDecisionType;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyRequest;

class QaTracePolicyRuleTest {

    private final QaTracePolicyRule rule = new QaTracePolicyRule();

    @Test
    void qaTraceRequest_recordAndSample() {
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                null, null, null, true, ParentContextState.ABSENT);

        SamplingPolicyDecision decision = rule.evaluate(request);

        assertThat(decision.decisionType()).isEqualTo(SamplingPolicyDecisionType.RECORD_AND_SAMPLE);
        assertThat(decision.reason()).isEqualTo(SamplingPolicyReason.QA_TRACE);
        assertThat(decision.winningRule()).isEqualTo("qa_trace");
    }

    @Test
    void nonQaTraceRequest_abstains() {
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                null, null, null, false, ParentContextState.ABSENT);

        // Правило не выносит решение (возвращает null) — это протокол «воздержался».
        assertThat(rule.evaluate(request)).isNull();
    }
}
