package space.br1440.platform.tracing.otel.javaagent.sampler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.runtime.sampling.model.ParentContextState;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingOutcome;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingStartupRules;
import space.br1440.platform.tracing.runtime.sampling.policy.SamplingPolicyEvaluator;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;

class TraceIdRatioParityTest {

    @Test
    void fractionalRatiosMatchPinnedOtelForValidTraceIds() {
        // given
        SamplingStartupRules rules = SamplingStartupRules.create(List.of(), List.of());
        Random random = new Random(1440);
        for (double ratio : new double[] {0.0001, 0.1, 0.5, 0.99}) {
            try (Sampler upstream = Sampler.traceIdRatioBased(ratio)) {
                for (int i = 0; i < 1000; i++) {
                    String id = "0000000000000001" + String.format("%016x", random.nextLong());
                    SamplingPolicyRequest request = new SamplingPolicyRequest(null, id, null, false, ParentContextState.ABSENT);

                    // when
                    SamplingOutcome actual = SamplingPolicyEvaluator.evaluate(request, true, Map.of(), ratio, rules);
                    SamplingDecision reference = upstream.shouldSample(Context.root(), id, "test", SpanKind.INTERNAL,
                            Attributes.empty(), List.of()).getDecision();

                    // then
                    assertThat(actual.sampled()).as("ratio=%s traceId=%s", ratio, id)
                            .isEqualTo(reference == SamplingDecision.RECORD_AND_SAMPLE);
                }
            }
        }
    }

    @Test
    void ratioOneIntentionallySamplesMaximumLowerBitsUnlikePinnedOtel() {
        // given
        String id = "00000000000000017fffffffffffffff";
        SamplingPolicyRequest request = new SamplingPolicyRequest(null, id, null, false, ParentContextState.ABSENT);

        try (Sampler upstream = Sampler.traceIdRatioBased(1)) {
            // when
            SamplingOutcome actual = SamplingPolicyEvaluator.evaluate(request, true, Map.of(), 1,
                    SamplingStartupRules.create(List.of(), List.of()));
            SamplingDecision reference = upstream.shouldSample(Context.root(), id, "test",
                    SpanKind.INTERNAL, Attributes.empty(), List.of()).getDecision();

            // then
            assertThat(actual).isEqualTo(SamplingOutcome.DEFAULT_SAMPLE);
            assertThat(reference).isEqualTo(SamplingDecision.DROP);
        }
    }
}
