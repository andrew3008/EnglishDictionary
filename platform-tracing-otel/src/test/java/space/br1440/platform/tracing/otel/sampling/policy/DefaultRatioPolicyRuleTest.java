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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRatioPolicyRuleTest {

    private final DefaultRatioPolicyRule rule = new DefaultRatioPolicyRule();

    @Test
    void defaultRatioOne_samples() {
        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFixtures.snapshot(true, List.of());
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                null, "00000000000000000000000000000000", null, false, ParentContextState.ABSENT);

        SamplingPolicyDecision decision = rule.evaluate(request, snapshot);

        assertThat(decision.decisionType()).isEqualTo(SamplingPolicyDecisionType.RECORD_AND_SAMPLE);
        assertThat(decision.reason()).isEqualTo(SamplingPolicyReason.DEFAULT_RATIO);
        assertThat(decision.winningRule()).isEqualTo("default_ratio");
    }

    @Test
    void defaultRatioZero_drops() {
        SamplingPolicySnapshot snapshot = new SamplingPolicySnapshot(
                true, List.of(), Set.of(), List.of(), 0.0);
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                null, "00000000000000000000000000000000", null, false, ParentContextState.ABSENT);

        SamplingPolicyDecision decision = rule.evaluate(request, snapshot);

        assertThat(decision.decisionType()).isEqualTo(SamplingPolicyDecisionType.DROP);
        assertThat(decision.reason()).isEqualTo(SamplingPolicyReason.DEFAULT_RATIO_DROP);
    }
}
