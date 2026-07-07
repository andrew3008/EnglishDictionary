package space.br1440.platform.tracing.test.harness;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;

import static org.assertj.core.api.Assertions.assertThat;

class SamplerHarnessTest {

    @Test
    void alwaysOn_возвращает_RECORD_AND_SAMPLE() {
        SamplerDecisionAssert.assertThat(SamplerHarness.of(Sampler.alwaysOn()).sample())
                .isRecordAndSample();
    }

    @Test
    void alwaysOff_возвращает_DROP() {
        SamplerDecisionAssert.assertThat(SamplerHarness.of(Sampler.alwaysOff()).sample())
                .isDrop();
    }

    @Test
    void накопленные_атрибуты_передаются_в_sampler() {
        SamplerHarness harness = SamplerHarness.of(Sampler.alwaysOn())
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("http.method", "GET")
                .putStringArrayAttribute("http.request.header.x-trace-on", "on");

        assertThat(harness.attributesSnapshot().asMap()).hasSize(2);
        assertThat(harness.sample().getDecision()).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
    }
}
