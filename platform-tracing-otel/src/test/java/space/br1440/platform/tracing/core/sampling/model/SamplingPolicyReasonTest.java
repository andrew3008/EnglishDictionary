package space.br1440.platform.tracing.core.sampling.model;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Исчерпывающая защита маппинга {@link SamplingPolicyReason} -> {@link PlatformSamplingReasons}.
 * <p>
 * Гарантирует, что reason-коды доменных решений не «уплывут» при рефакторинге. OTel-специфика
 * (RECORD_ONLY / fallback_drop) проверяется отдельно на стороне otel-extension adapter-теста, без
 * OTel-типов в core.
 */
class SamplingPolicyReasonTest {

    @Test
    void reasonCodes_mapToExpectedPlatformConstants() {
        assertThat(SamplingPolicyReason.KILL_SWITCH.reasonCode()).isEqualTo(PlatformSamplingReasons.KILL_SWITCH);
        assertThat(SamplingPolicyReason.HARD_DROP.reasonCode()).isEqualTo(PlatformSamplingReasons.DROP_PATH);
        assertThat(SamplingPolicyReason.FORCE_HEADER.reasonCode()).isEqualTo(PlatformSamplingReasons.FORCE_HEADER);
        assertThat(SamplingPolicyReason.QA_TRACE.reasonCode()).isEqualTo(PlatformSamplingReasons.QA_TRACE);
        assertThat(SamplingPolicyReason.PARENT_DECISION.reasonCode()).isEqualTo(PlatformSamplingReasons.PARENT_SAMPLED);
        assertThat(SamplingPolicyReason.PARENT_DROP.reasonCode()).isEqualTo(PlatformSamplingReasons.PARENT_DROP);
        assertThat(SamplingPolicyReason.ROUTE_RATIO.reasonCode()).isEqualTo(PlatformSamplingReasons.ROUTE_RATIO);
        assertThat(SamplingPolicyReason.ROUTE_RATIO_DROP.reasonCode()).isEqualTo(PlatformSamplingReasons.ROUTE_RATIO_DROP);
        assertThat(SamplingPolicyReason.DEFAULT_RATIO.reasonCode()).isEqualTo(PlatformSamplingReasons.GLOBAL_RATIO);
        assertThat(SamplingPolicyReason.DEFAULT_RATIO_DROP.reasonCode()).isEqualTo(PlatformSamplingReasons.GLOBAL_RATIO_DROP);
    }

    @Test
    void noMatch_hasNullReasonCode() {
        assertThat(SamplingPolicyReason.NO_MATCH.reasonCode()).isNull();
    }

    @Test
    void everyReasonExceptNoMatch_hasNonNullCode() {
        for (SamplingPolicyReason reason : SamplingPolicyReason.values()) {
            if (reason == SamplingPolicyReason.NO_MATCH) {
                continue;
            }
            assertThat(reason.reasonCode())
                    .as("reasonCode for %s", reason)
                    .isNotNull();
        }
    }
}
