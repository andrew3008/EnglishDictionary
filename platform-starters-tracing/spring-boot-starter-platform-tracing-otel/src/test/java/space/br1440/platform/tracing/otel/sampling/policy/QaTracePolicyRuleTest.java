package space.br1440.platform.tracing.otel.sampling.policy;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.sampling.model.ParentContextState;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecisionType;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshot;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshotFixtures;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QaTracePolicyRuleTest {

    private final QaTracePolicyRule rule = new QaTracePolicyRule();
    private final SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFixtures.snapshot(true, List.of());

    @Test
    void ruleName_isQaTrace() {
        assertThat(rule.ruleName()).isEqualTo("qa_trace");
    }

    @Test
    void qaTraceRequest_recordAndSample() {
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                null, null, null, true, ParentContextState.ABSENT);

        SamplingPolicyDecision decision = rule.evaluate(request, snapshot);

        assertThat(decision.decisionType()).isEqualTo(SamplingPolicyDecisionType.RECORD_AND_SAMPLE);
        assertThat(decision.reason()).isEqualTo(SamplingPolicyReason.QA_TRACE);
        assertThat(decision.winningRule()).isEqualTo("qa_trace");
    }

    @Test
    void nonQaTraceRequest_abstains() {
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                null, null, null, false, ParentContextState.ABSENT);

        // Правило не выносит решение (возвращает null) — это протокол «воздержался».
        assertThat(rule.evaluate(request, snapshot)).isNull();
    }
}
