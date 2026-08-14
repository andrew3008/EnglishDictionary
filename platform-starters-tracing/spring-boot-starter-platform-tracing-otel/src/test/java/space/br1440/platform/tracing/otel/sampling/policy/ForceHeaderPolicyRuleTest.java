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

class ForceHeaderPolicyRuleTest {

    private final ForceHeaderPolicyRule rule = new ForceHeaderPolicyRule();

    @Test
    void matchingForceValue_recordsAndSamples() {
        SamplingPolicySnapshot snapshot = new SamplingPolicySnapshot(
                true, List.of(), Set.of("on"), List.of(), 0.0);
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                null, null, "on", false, ParentContextState.ABSENT);

        SamplingPolicyDecision decision = rule.evaluate(request, snapshot);

        assertThat(decision.decisionType()).isEqualTo(SamplingPolicyDecisionType.RECORD_AND_SAMPLE);
        assertThat(decision.reason()).isEqualTo(SamplingPolicyReason.FORCE_HEADER);
        assertThat(decision.winningRule()).isEqualTo("force_header");
    }

    @Test
    void customForceValue_isCaseInsensitive() {
        SamplingPolicySnapshot snapshot = new SamplingPolicySnapshot(
                true, List.of(), Set.of("custom-force"), List.of(), 0.0);
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                null, null, "Custom-Force", false, ParentContextState.ABSENT);

        assertThat(rule.evaluate(request, snapshot).decisionType())
                .isEqualTo(SamplingPolicyDecisionType.RECORD_AND_SAMPLE);
    }

    @Test
    void nonMatchingForceValue_abstains() {
        SamplingPolicySnapshot snapshot = new SamplingPolicySnapshot(
                true, List.of(), Set.of("on"), List.of(), 0.0);
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                null, null, "off", false, ParentContextState.ABSENT);

        assertThat(rule.evaluate(request, snapshot)).isNull();
    }

    @Test
    void emptyForceRecordValues_abstains() {
        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFixtures.snapshot(true, List.of());
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                null, null, "on", false, ParentContextState.ABSENT);

        assertThat(rule.evaluate(request, snapshot)).isNull();
    }
}
