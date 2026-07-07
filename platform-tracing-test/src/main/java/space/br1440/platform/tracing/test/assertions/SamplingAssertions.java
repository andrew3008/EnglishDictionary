package space.br1440.platform.tracing.test.assertions;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

/**
 * Утверждения для characterization sampling-тестов.
 */
public final class SamplingAssertions {

    private static final AttributeKey<String> SAMPLING_REASON =
            AttributeKey.stringKey(PlatformAttributes.PLATFORM_SAMPLING_REASON);

    private SamplingAssertions() {
    }

    public static void assertDecision(SamplingResult result, SamplingDecision expected) {
        SamplerDecisionAssert.assertThat(result);
        switch (expected) {
            case RECORD_AND_SAMPLE -> SamplerDecisionAssert.assertThat(result).isRecordAndSample();
            case RECORD_ONLY -> SamplerDecisionAssert.assertThat(result).isRecordOnly();
            case DROP -> SamplerDecisionAssert.assertThat(result).isDrop();
            default -> throw new IllegalArgumentException("Unsupported decision: " + expected);
        }
    }

    public static void assertSamplingReason(SamplingResult result, String expectedReason) {
        if (expectedReason == null) {
            SamplerDecisionAssert.assertThat(result).hasNoAttribute(SAMPLING_REASON);
        } else {
            SamplerDecisionAssert.assertThat(result).hasAttribute(SAMPLING_REASON, expectedReason);
        }
    }
}
