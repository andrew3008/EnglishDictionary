package space.br1440.platform.tracing.otel.javaagent.sampler;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.core.sampling.engine.SamplingPolicyEngine;
import space.br1440.platform.tracing.core.sampling.model.ParentContextState;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyDecision;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyDecisionType;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyReason;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicySnapshot;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Паритет детерминированного ratio-сэмплирования core с OTel {@code TraceIdRatioBased}.
 * <p>
 * Используем публичную точку входа {@link SamplingPolicyEngine#productionEngine()}: snapshot без
 * kill-switch/drop/force/route и request без force/qa/parent гарантированно доходят до правила
 * {@code default_ratio}. Тест не пересекает границу слоёв напрямую (не конструирует правило и движок
 * руками) — это уважает package-private видимость движка и «not extension API» статус правил.
 */
class TraceIdRatioParityTest {

    @Test
    void coreDefaultRatio_matchesOtelTraceIdRatioBased() {
        double ratio = 0.5;
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        SamplingPolicyEngine engine = SamplingPolicyEngine.productionEngine();
        SamplingPolicySnapshot snapshot = new SamplingPolicySnapshot(
                true, List.of(), Set.of(), List.of(), ratio);
        SamplingPolicyRequest request = new SamplingPolicyRequest(
                null, traceId, null, false, ParentContextState.ABSENT);

        SamplingPolicyDecision coreDecision = engine.evaluate(request, snapshot);
        SamplingDecision otelDecision = Sampler.traceIdRatioBased(ratio).shouldSample(
                Context.root(), traceId, "span", SpanKind.SERVER, null, null).getDecision();

        // Snapshot/Request подобраны так, что решает именно default_ratio (предыдущие правила воздержались).
        assertThat(coreDecision.reason()).isIn(
                SamplingPolicyReason.DEFAULT_RATIO, SamplingPolicyReason.DEFAULT_RATIO_DROP);
        if (otelDecision == SamplingDecision.RECORD_AND_SAMPLE) {
            assertThat(coreDecision.decisionType()).isEqualTo(SamplingPolicyDecisionType.RECORD_AND_SAMPLE);
        } else {
            assertThat(coreDecision.decisionType()).isEqualTo(SamplingPolicyDecisionType.DROP);
        }
    }
}
