package space.br1440.platform.tracing.otel.extension.sampler;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;
import space.br1440.platform.tracing.test.harness.SamplerHarness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт {@code RouteRatioRule} на уровне {@code CompositeSampler} (PR-E gap-check:
 * route-ratio ветка не имела ни одного unit-теста; ADR-runtime-sampling-policy, разделы 2.1/2.3).
 */
class CompositeSamplerRouteRatioTest {

    private static final AttributeKey<String> REASON =
            AttributeKey.stringKey(PlatformAttributes.PLATFORM_SAMPLING_REASON);

    @Test
    void route_ratio_1_сэмплирует_при_глобальном_ratio_0() {
        // Route-правило стоит в цепочке ДО DefaultRatioRule — глобальный 0.0 не должен задавить route 1.0.
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of("on"), Map.of("/api/v1/critical", 1.0), 0.0);
        CompositeSampler sampler = new CompositeSampler(holder);

        SamplingResult result = SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("url.path", "/api/v1/critical/checkout")
                .sample();

        SamplerDecisionAssert.assertThat(result).isRecordAndSample();
        assertThat(result.getAttributes().get(REASON))
                .isEqualTo(PlatformSamplingReasons.ROUTE_RATIO);
    }

    @Test
    void route_ratio_0_дропает_при_глобальном_ratio_1() {
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of("on"), Map.of("/api/v1/noisy", 0.0), 1.0);
        CompositeSampler sampler = new CompositeSampler(holder);

        SamplingResult result = SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("url.path", "/api/v1/noisy/search")
                .sample();

        SamplerDecisionAssert.assertThat(result).isDrop();
        assertThat(result.getAttributes().get(REASON))
                .isEqualTo(PlatformSamplingReasons.ROUTE_RATIO_DROP);
    }

    @Test
    void путь_вне_route_ratios_падает_на_глобальный_ratio() {
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of("on"), Map.of("/api/v1/noisy", 0.0), 1.0);
        CompositeSampler sampler = new CompositeSampler(holder);

        SamplingResult result = SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("url.path", "/api/v1/orders")
                .sample();

        SamplerDecisionAssert.assertThat(result).isRecordAndSample();
        assertThat(result.getAttributes().get(REASON))
                .isEqualTo(PlatformSamplingReasons.GLOBAL_RATIO);
    }

    @Test
    void решение_детерминировано_по_traceId_при_дробном_ratio() {
        // ADR раздел 2.3: traceIdRatioBased — функция traceId; повторные вызовы и независимые
        // инстансы сэмплера с одинаковой конфигурацией обязаны давать одно и то же решение.
        SamplerStateHolder holderA = new SamplerStateHolder(
                true, List.of(), List.of("on"), Map.of("/api", 0.5), 0.0);
        SamplerStateHolder holderB = new SamplerStateHolder(
                true, List.of(), List.of("on"), Map.of("/api", 0.5), 0.0);
        CompositeSampler samplerA = new CompositeSampler(holderA);
        CompositeSampler samplerB = new CompositeSampler(holderB);

        String traceId = "0af7651916cd43dd8448eb211c80319c";
        SamplingDecision first = sampleDecision(samplerA, traceId);
        for (int i = 0; i < 100; i++) {
            assertThat(sampleDecision(samplerA, traceId))
                    .as("повторный вызов #%s с тем же traceId", i)
                    .isEqualTo(first);
        }
        assertThat(sampleDecision(samplerB, traceId))
                .as("независимый инстанс с той же конфигурацией")
                .isEqualTo(first);
    }

    @Test
    void overlapping_prefixes_most_specific_wins_deterministically() {
        // PR-9G (Opus B2): /api=0.0, /api/v2=0.0, /api/v2/orders=1.0 → самый специфичный (1.0) сэмплирует.
        // LinkedHashMap в «обратном» порядке доказывает независимость от порядка вставки.
        java.util.LinkedHashMap<String, Double> ratios = new java.util.LinkedHashMap<>();
        ratios.put("/api", 0.0);
        ratios.put("/api/v2", 0.0);
        ratios.put("/api/v2/orders", 1.0);
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of("on"), ratios, 0.0);
        CompositeSampler sampler = new CompositeSampler(holder);

        SamplingResult result = SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("url.path", "/api/v2/orders/123")
                .sample();

        SamplerDecisionAssert.assertThat(result).isRecordAndSample();
        assertThat(result.getAttributes().get(REASON))
                .isEqualTo(PlatformSamplingReasons.ROUTE_RATIO);
    }

    @Test
    void hard_drop_сильнее_route_ratio_1() {
        // Порядок цепочки (ADR 2.1): HardDropRule (#2) стоит до RouteRatioRule (#6).
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of("/api/v1/critical"), List.of("on"), Map.of("/api/v1/critical", 1.0), 1.0);
        CompositeSampler sampler = new CompositeSampler(holder);

        SamplingResult result = SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("url.path", "/api/v1/critical/checkout")
                .sample();

        SamplerDecisionAssert.assertThat(result).isDrop();
        assertThat(result.getAttributes().get(REASON))
                .isEqualTo(PlatformSamplingReasons.DROP_PATH);
    }

    private static SamplingDecision sampleDecision(CompositeSampler sampler, String traceId) {
        return SamplerHarness.of(sampler)
                .traceId(traceId)
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("url.path", "/api/v1/orders")
                .sample()
                .getDecision();
    }
}
