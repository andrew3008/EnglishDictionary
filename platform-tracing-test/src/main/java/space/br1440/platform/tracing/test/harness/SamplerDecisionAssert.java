package space.br1440.platform.tracing.test.harness;

import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fluent assertion wrapper over a {@link SamplingResult} returned by
 * {@link SamplerHarness#sample(String)}.
 *
 * <p>Delegates all equality checks to AssertJ so failure messages are readable.
 *
 * @since 5.2
 */
public final class SamplerDecisionAssert {

    private final SamplingResult result;

    SamplerDecisionAssert(SamplingResult result) {
        this.result = result;
    }

    /**
     * Asserts that the sampler decided to record and sample the span
     * (i.e. {@link SamplingDecision#RECORD_AND_SAMPLE}).
     *
     * @return this (fluent)
     */
    public SamplerDecisionAssert assertSampled() {
        assertThat(result.getDecision())
                .as("Expected RECORD_AND_SAMPLE but got %s", result.getDecision())
                .isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        return this;
    }

    /**
     * Asserts that the sampler decided to drop the span
     * (i.e. {@link SamplingDecision#DROP}).
     *
     * @return this (fluent)
     */
    public SamplerDecisionAssert assertDropped() {
        assertThat(result.getDecision())
                .as("Expected DROP but got %s", result.getDecision())
                .isEqualTo(SamplingDecision.DROP);
        return this;
    }

    /**
     * Asserts that the sampler decided to record (but not export) the span
     * (i.e. {@link SamplingDecision#RECORD_ONLY}).
     *
     * @return this (fluent)
     */
    public SamplerDecisionAssert assertRecordOnly() {
        assertThat(result.getDecision())
                .as("Expected RECORD_ONLY but got %s", result.getDecision())
                .isEqualTo(SamplingDecision.RECORD_ONLY);
        return this;
    }

    /**
     * Asserts an exact {@link SamplingDecision}.
     *
     * @param expected the expected decision
     * @return this (fluent)
     */
    public SamplerDecisionAssert hasDecision(SamplingDecision expected) {
        assertThat(result.getDecision())
                .as("Sampling decision mismatch")
                .isEqualTo(expected);
        return this;
    }

    /**
     * Exposes the raw {@link SamplingResult} for custom assertions.
     *
     * @return the underlying result
     */
    public SamplingResult result() {
        return result;
    }
}
