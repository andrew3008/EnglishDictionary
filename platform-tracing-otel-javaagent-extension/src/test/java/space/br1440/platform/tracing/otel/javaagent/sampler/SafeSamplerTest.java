package space.br1440.platform.tracing.otel.javaagent.sampler;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.safety.TracingDiagnostics;
import space.br1440.platform.tracing.test.harness.SamplerHarness;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failure-path тесты {@link SafeSampler}: падение делегата не уходит в application thread,
 * применяется fail-closed DROP, degraded-mode перестаёт дёргать стабильно падающий делегат.
 */
class SafeSamplerTest {

    private static final Sampler ALWAYS_THROWS = new Sampler() {
        @Override
        public SamplingResult shouldSample(Context parentContext, String traceId, String name,
                                           SpanKind spanKind, Attributes attributes, List<LinkData> parentLinks) {
            throw new IllegalStateException("делегат сломан");
        }

        @Override
        public String getDescription() {
            throw new IllegalStateException("описание недоступно");
        }
    };

    @Test
    void падение_делегата_не_бросается_и_закрывается_drop() {
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        SafeSampler safe = new SafeSampler(ALWAYS_THROWS, diagnostics);

        SamplingResult result = SamplerHarness.of(safe).sample();

        assertThat(result.getDecision()).isEqualTo(SamplingDecision.DROP);
        assertThat(diagnostics.getSamplerFailures()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void getDescription_безопасен_при_падении_делегата() {
        SafeSampler safe = new SafeSampler(ALWAYS_THROWS, new TracingDiagnostics());
        assertThat(safe.getDescription()).isEqualTo("SafeSampler{description_unavailable}");
    }

    @Test
    void null_результат_делегата_трактуется_как_сбой() {
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

        SamplingResult result = SamplerHarness.of(safe).sample();

        assertThat(result.getDecision()).isEqualTo(SamplingDecision.DROP);
        assertThat(diagnostics.getSamplerFailures()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void degraded_mode_перестаёт_дёргать_стабильно_падающий_делегат() {
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
        // Дефолтный DegradedModeController: порог 5 сбоев, cooldown 60с.
        SafeSampler safe = new SafeSampler(counting, new TracingDiagnostics());
        SamplerHarness harness = SamplerHarness.of(safe);

        for (int i = 0; i < 10; i++) {
            assertThat(harness.sample().getDecision()).isEqualTo(SamplingDecision.DROP);
        }

        // После 5 сбоев breaker OPEN — делегат больше не вызывается (защита hot-path от лишней работы).
        assertThat(delegateCalls.get())
                .as("делегат вызывался не более порога открытия breaker")
                .isLessThanOrEqualTo(5);
    }
}
