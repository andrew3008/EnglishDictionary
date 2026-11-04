package space.br1440.platform.tracing.otel.javaagent.sampler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingStartupRules;
import space.br1440.platform.tracing.test.harness.SamplerHarness;
import space.br1440.platform.tracing.test.harness.SamplingContextFactory;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

class SamplingParentContextTest {

    private static final String ID = "00000000000000000000000000000001";
    private static final String SPAN_ID = "0000000000000001";

    @ParameterizedTest
    @CsvSource({"false,true", "true,true", "false,false", "true,false"})
    void honorsBothParentLocationsAndPreservesTracestate(boolean remote, boolean sampled) {
        // given
        TraceState state = TraceState.builder().put("vendor", "value").build();
        TraceFlags flags = sampled ? TraceFlags.getSampled() : TraceFlags.getDefault();
        SpanContext parent = remote ? SpanContext.createFromRemoteParent(ID, SPAN_ID, flags, state)
                : SpanContext.create(ID, SPAN_ID, flags, state);
        CompositeSampler sampler = sampler(sampled ? 0.0 : 1.0);

        // when
        SamplingResult result = SamplerHarness.of(sampler).parentContext(Context.root().with(Span.wrap(parent)))
                .putStringAttribute("url.path", "/api").sample();

        // then
        assertThat(result.getDecision()).isEqualTo(sampled ? SamplingDecision.RECORD_AND_SAMPLE : SamplingDecision.DROP);
        assertThat(result.getAttributes().get(AttributeKey.stringKey("platform.sampling.reason")))
                .isEqualTo(sampled ? "parent_sampled" : "parent_drop");
        assertThat(result.getUpdatedTraceState(state)).isEqualTo(state);
    }

    @ParameterizedTest
    @CsvSource({"/api,on,false,force_header", "/api,,true,qa_trace", "/drop,on,true,drop_path"})
    void overridesRemoteUnsampledParentInDefinedOrder(String path, String force, boolean qa, String expectedReason) {
        // given
        SpanContext parent = SpanContext.createFromRemoteParent(ID, SPAN_ID, TraceFlags.getDefault(), TraceState.getDefault());
        Context context = (qa ? SamplingContextFactory.withForceAndQa(force) : SamplingContextFactory.withForceHeader(force))
                .with(Span.wrap(parent));

        // when
        SamplingResult result = SamplerHarness.of(sampler(0)).parentContext(context).putStringAttribute("url.path", path).sample();

        // then
        assertThat(result.getAttributes().get(AttributeKey.stringKey("platform.sampling.reason"))).isEqualTo(expectedReason);
        assertThat(result.getDecision()).isEqualTo(path.equals("/drop") ? SamplingDecision.DROP : SamplingDecision.RECORD_AND_SAMPLE);
    }

    @ParameterizedTest
    @EnumSource(SpanKind.class)
    void invalidParentFallsThroughForEverySpanKind(SpanKind kind) {
        // given
        Context parent = Context.root().with(Span.wrap(SpanContext.getInvalid()));

        // when
        SamplingResult result = SamplerHarness.of(sampler(1)).spanKind(kind).parentContext(parent).sample();

        // then
        assertThat(result.getAttributes().get(AttributeKey.stringKey("platform.sampling.reason"))).isEqualTo("global_ratio");
    }

    private static CompositeSampler sampler(double ratio) {
        return new CompositeSampler(TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true,
                Map.of("/api", ratio), ratio), SamplingStartupRules.create(List.of("/drop"), List.of("on")));
    }
}
