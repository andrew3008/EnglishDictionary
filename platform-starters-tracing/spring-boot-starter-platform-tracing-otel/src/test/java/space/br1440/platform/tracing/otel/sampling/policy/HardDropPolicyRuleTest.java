package space.br1440.platform.tracing.otel.sampling.policy;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyDecisionType;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshot;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshotFixtures;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HardDropPolicyRuleTest {

    private final HardDropPolicyRule rule = new HardDropPolicyRule();

    @Test
    void matchingPrefix_returnsDropWithDropPathReason() {
        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFixtures.snapshot(true, List.of("/actuator/health"));
        SamplingPolicyDecision decision = rule.evaluate(
                new SamplingPolicyRequest("/actuator/health/check"), snapshot);

        assertThat(decision.decisionType()).isEqualTo(SamplingPolicyDecisionType.DROP);
        assertThat(decision.reason()).isEqualTo(SamplingPolicyReason.HARD_DROP);
        assertThat(decision.reason().reasonCode()).isEqualTo(PlatformSamplingReasons.DROP_PATH);
        assertThat(decision.winningRule()).isEqualTo("hard_drop");
    }

    @Test
    void nonMatchingPrefix_returnsNull() {
        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFixtures.snapshot(true, List.of("/actuator/health"));
        assertThat(rule.evaluate(new SamplingPolicyRequest("/api/orders"), snapshot)).isNull();
    }

    @Test
    void emptyUrlPath_returnsNull() {
        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFixtures.snapshot(true, List.of("/actuator/health"));
        assertThat(rule.evaluate(new SamplingPolicyRequest(""), snapshot)).isNull();
        assertThat(rule.evaluate(new SamplingPolicyRequest(null), snapshot)).isNull();
    }

    @Test
    void emptyDropList_returnsNull() {
        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFixtures.snapshot(true, List.of());
        assertThat(rule.evaluate(new SamplingPolicyRequest("/actuator/health"), snapshot)).isNull();
    }
}
