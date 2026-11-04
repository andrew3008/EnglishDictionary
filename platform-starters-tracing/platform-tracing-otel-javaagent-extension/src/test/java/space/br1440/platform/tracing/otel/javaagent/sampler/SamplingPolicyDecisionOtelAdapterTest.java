package space.br1440.platform.tracing.otel.javaagent.sampler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingOutcome;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

class SamplingPolicyDecisionOtelAdapterTest {

    @ParameterizedTest
    @CsvSource({
            "KILL_SWITCH,DROP,kill_switch,kill_switch",
            "HARD_DROP,DROP,drop_path,hard_drop",
            "FORCE_HEADER,RECORD_AND_SAMPLE,force_header,force_header",
            "QA_TRACE,RECORD_AND_SAMPLE,qa_trace,qa_trace",
            "PARENT_SAMPLED,RECORD_AND_SAMPLE,parent_sampled,parent_decision",
            "PARENT_DROP,DROP,parent_drop,parent_decision",
            "ROUTE_SAMPLE,RECORD_AND_SAMPLE,route_ratio,route_ratio",
            "ROUTE_DROP,DROP,route_ratio_drop,route_ratio",
            "DEFAULT_SAMPLE,RECORD_AND_SAMPLE,global_ratio,default_ratio",
            "DEFAULT_DROP,DROP,global_ratio_drop,default_ratio"
    })
    void mapsOutcomeWithoutLosingReasonOrParentTracestate(SamplingOutcome outcome, SamplingDecision decision,
                                                         String reason, String winner) {
        // given
        TraceState parentState = TraceState.builder().put("vendor", "state").build();

        // when
        SamplingResult result = SamplingPolicyOtelAdapter.toSamplingResult(outcome);

        // then
        assertThat(result.getDecision()).isEqualTo(decision);
        assertThat(result.getUpdatedTraceState(parentState)).isEqualTo(parentState);
        assertThat(result).isSameAs(SamplingPolicyOtelAdapter.toSamplingResult(outcome));
        assertThat(result.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_SAMPLING_REASON)))
                .isEqualTo(outcome == SamplingOutcome.KILL_SWITCH ? null : reason);
        assertThat(outcome.reasonCode()).isEqualTo(reason);
        assertThat(outcome.winningRule()).isEqualTo(winner);
        assertThat(outcome.sampled()).isEqualTo(decision == SamplingDecision.RECORD_AND_SAMPLE);
        assertThat(SamplingPolicyOtelAdapter.metricKey(outcome)).isEqualTo(decision.name() + ":" + reason);
    }
}
