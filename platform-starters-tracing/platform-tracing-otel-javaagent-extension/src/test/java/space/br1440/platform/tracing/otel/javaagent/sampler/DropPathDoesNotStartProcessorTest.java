package space.br1440.platform.tracing.otel.javaagent.sampler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.test.harness.SamplerHarness;

class DropPathDoesNotStartProcessorTest {

    @Test
    void dropPathSamplerReturnsDrop() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of(), 1.0d);
        CompositeSampler sampler = new CompositeSampler(
                holder, CategoryBSamplingAccess.snapshot(List.of("/internal/state"), List.of("on")));

        // when
        SamplingResult result = SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("url.path", "/internal/state/leaks/1")
                .sample();

        // then
        assertThat(result.getDecision()).isEqualTo(SamplingDecision.DROP);
    }

    @Test
    void dropDecisionDoesNotInvokeProcessorOnStart() {
        // given
        CountingProcessor processor = new CountingProcessor();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOff())
                .addSpanProcessor(processor)
                .build();

        // when
        provider.get("drop").spanBuilder("dropped").setParent(Context.root()).startSpan().end();
        provider.close();

        // then
        assertThat(processor.starts.get()).isZero();
    }

    private static final class CountingProcessor implements SpanProcessor {
        private final AtomicInteger starts = new AtomicInteger();

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
            starts.incrementAndGet();
        }

        @Override
        public boolean isStartRequired() {
            return true;
        }

        @Override
        public void onEnd(ReadableSpan span) {
        }

        @Override
        public boolean isEndRequired() {
            return false;
        }
    }
}
