package space.br1440.platform.tracing.otel.javaagent.sampler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingStartupRules;
import space.br1440.platform.tracing.test.harness.SamplerHarness;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class SafeSamplerTest {

    private static final Sampler ALWAYS_THROWS = new Sampler() {
        @Override
        public SamplingResult shouldSample(Context parentContext, String traceId, String name,
                                           SpanKind spanKind, Attributes attributes, List<LinkData> parentLinks) {
            throw new IllegalStateException("delegate is broken");
        }

        @Override
        public String getDescription() {
            throw new IllegalStateException("description is unavailable");
        }
    };

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    @Test
    void delegate_failure_is_not_thrown_and_falls_back_to_drop() {
        // given
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        SafeSampler safe = new SafeSampler(ALWAYS_THROWS, diagnostics);

        // when
        SamplingResult result = SamplerHarness.of(safe).sample();

        // then
        assertThat(result.getDecision()).isEqualTo(SamplingDecision.DROP);
        assertThat(diagnostics.getSamplerFailures()).isGreaterThanOrEqualTo(1);
        assertThat(samplerEvent().fields()).containsEntry("reason", "THREW");
        assertThat(samplerEvent().errorType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(samplerEvent().throwableDigest()).contains(IllegalStateException.class.getName());
    }

    @Test
    void getDescription_is_safe_when_delegate_fails() {
        // given
        SafeSampler safe = new SafeSampler(ALWAYS_THROWS, new TracingDiagnostics());
        // when/then
        assertThat(safe.getDescription()).isEqualTo("SafeSampler{description_unavailable}");
    }

    @Test
    void null_delegate_result_is_treated_as_failure() {
        // given
        Sampler returnsNull = new Sampler() {
            @Override
            public SamplingResult shouldSample(Context parentContext, String traceId, String name,
                                               SpanKind spanKind, Attributes attributes, List<LinkData> parentLinks) {
                return null;
            }

            @Override
            public String getDescription() {
                return "returnsNull";
            }
        };
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        SafeSampler safe = new SafeSampler(returnsNull, diagnostics);

        // when
        SamplingResult result = SamplerHarness.of(safe).sample();

        // then
        assertThat(result.getDecision()).isEqualTo(SamplingDecision.DROP);
        assertThat(diagnostics.getSamplerFailures()).isGreaterThanOrEqualTo(1);
        assertThat(samplerEvent().fields()).containsEntry("reason", "RETURNED_NULL");
        assertThat(samplerEvent().errorType()).isNull();
        assertThat(samplerEvent().throwableDigest()).isNull();
    }

    @Test
    void degraded_mode_stops_calling_consistently_failing_delegate() {
        // given
        AtomicInteger delegateCalls = new AtomicInteger();
        Sampler counting = new Sampler() {
            @Override
            public SamplingResult shouldSample(Context parentContext, String traceId, String name,
                                               SpanKind spanKind, Attributes attributes, List<LinkData> parentLinks) {
                delegateCalls.incrementAndGet();
                throw new IllegalStateException("fail");
            }

            @Override
            public String getDescription() {
                return "counting";
            }
        };

        SafeSampler safe = new SafeSampler(counting, new TracingDiagnostics());
        SamplerHarness harness = SamplerHarness.of(safe);

        // when/then
        for (int i = 0; i < 10; i++) {
            assertThat(harness.sample().getDecision()).isEqualTo(SamplingDecision.DROP);
        }

        // then
        assertThat(delegateCalls.get()).isEqualTo(5);
    }

    @Test
    void fatal_delegate_error_is_propagated_and_counted_by_breaker() {
        // given
        AtomicInteger delegateCalls = new AtomicInteger();
        Sampler linkageBroken = new Sampler() {
            @Override
            public SamplingResult shouldSample(Context parentContext, String traceId, String name,
                                               SpanKind spanKind, Attributes attributes, List<LinkData> parentLinks) {
                delegateCalls.incrementAndGet();
                throw new NoClassDefFoundError("missing");
            }

            @Override
            public String getDescription() {
                return "linkageBroken";
            }
        };
        SafeSampler safe = new SafeSampler(linkageBroken, new TracingDiagnostics());
        SamplerHarness harness = SamplerHarness.of(safe);

        // when
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(harness::sample).isInstanceOf(NoClassDefFoundError.class);
        }
        SamplingResult afterOpen = harness.sample();

        // then
        assertThat(afterOpen.getDecision()).isEqualTo(SamplingDecision.DROP);
        assertThat(delegateCalls.get()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0000000000000000zzzzzzzzzzzzzzzz", "0000000000000000fffffffffffffffff"})
    void malformedNumericTraceIdFailsClosedWithoutPolicyCounter(String traceId) {
        // given
        CompositeSampler core = fractionalCore();
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        SafeSampler safe = new SafeSampler(core, diagnostics);

        // when
        SamplingResult result = safe.shouldSample(Context.root(), traceId, "test", SpanKind.SERVER,
                Attributes.empty(), List.of());

        // then
        assertThat(result.getDecision()).isEqualTo(SamplingDecision.DROP);
        assertThat(result.getAttributes().isEmpty()).isTrue();
        assertThat(core.getDecisionCounts()).isEmpty();
        assertThat(diagnostics.getSamplerFailures()).isEqualTo(1);
        assertThat(samplerEvent().errorType()).isEqualTo(NumberFormatException.class.getName());
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = "0000000000000000000000000000001")
    void shortTraceIdUsesDefensivePolicyDrop(String traceId) {
        // given
        CompositeSampler core = fractionalCore();
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        SafeSampler safe = new SafeSampler(core, diagnostics);

        // when
        SamplingResult result = safe.shouldSample(Context.root(), traceId, "test", SpanKind.SERVER,
                Attributes.empty(), List.of());

        // then
        assertThat(result.getDecision()).isEqualTo(SamplingDecision.DROP);
        assertThat(core.getDecisionCount("DROP", "global_ratio_drop")).isEqualTo(1);
        assertThat(diagnostics.getSamplerFailures()).isZero();
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void nullTraceIdRemainsAnExplicitSdkEntryPrecondition() {
        // given
        CompositeSampler core = fractionalCore();
        SafeSampler safe = new SafeSampler(core, new TracingDiagnostics());

        // when
        Throwable failure = catchThrowable(() -> safe.shouldSample(
                Context.root(), null, "test", SpanKind.SERVER, Attributes.empty(), List.of()));

        // then
        assertThat(failure).isInstanceOf(NullPointerException.class);
        assertThat(core.getDecisionCounts()).isEmpty();
    }

    private static CompositeSampler fractionalCore() {
        return new CompositeSampler(TracingSettingsSourceTestFixtures.boundSampling(
                AgentInstanceId.random(), true, Map.of(), 0.5), SamplingStartupRules.create(List.of(), List.of()));
    }

    private CapturedLogEvent samplerEvent() {
        return logs.events().stream()
                .filter(event -> SafeSampler.class.getName().equals(event.logger()))
                .findFirst()
                .orElseThrow();
    }
}
