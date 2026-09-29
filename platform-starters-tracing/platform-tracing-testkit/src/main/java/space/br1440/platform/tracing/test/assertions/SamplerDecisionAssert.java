package space.br1440.platform.tracing.test.assertions;

import java.util.Objects;

import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

public final class SamplerDecisionAssert extends AbstractAssert<SamplerDecisionAssert, SamplingResult> {

    private SamplerDecisionAssert(SamplingResult actual) {
        super(actual, SamplerDecisionAssert.class);
    }

    public static SamplerDecisionAssert assertThat(SamplingResult actual) {
        return new SamplerDecisionAssert(actual);
    }

    public void isRecordAndSample() {
        isNotNull();

        if (actual.getDecision() != SamplingDecision.RECORD_AND_SAMPLE) {
            failWithMessage("Expected RECORD_AND_SAMPLE, but was: %s", actual.getDecision());
        }
    }

    public void assertSampled() {
        isRecordAndSample();
    }

    public void isRecordOnly() {
        isNotNull();

        if (actual.getDecision() != SamplingDecision.RECORD_ONLY) {
            failWithMessage("Expected RECORD_ONLY, but was: %s", actual.getDecision());
        }
    }

    public void isDrop() {
        isNotNull();

        if (actual.getDecision() != SamplingDecision.DROP) {
            failWithMessage("Expected DROP, but was: %s", actual.getDecision());
        }
    }

    public void assertDropped() {
        isDrop();
    }

    public void hasDecision(SamplingDecision expected) {
        isNotNull();
        Objects.requireNonNull(expected, "expected");
        if (actual.getDecision() != expected) {
            failWithMessage("Expected %s, but was: %s", expected, actual.getDecision());
        }
    }

    public SamplingResult result() {
        return actual;
    }

    public <T> void hasAttribute(AttributeKey<T> key, T expectedValue) {
        isNotNull();
        T actualValue = actual.getAttributes().get(key);
        Assertions.assertThat(actualValue)
                .as("attribute '%s' in SamplingResult", key.getKey())
                .isEqualTo(Objects.requireNonNull(expectedValue, "expectedValue"));
    }

    public void hasNoAttribute(AttributeKey<?> key) {
        isNotNull();
        Object actualValue = actual.getAttributes().get(key);
        Assertions.assertThat(actualValue)
                .as("attribute '%s' in SamplingResult must be absent", key.getKey())
                .isNull();
    }
}
