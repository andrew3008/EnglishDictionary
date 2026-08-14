package space.br1440.platform.tracing.otel.sampling.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-тесты детерминированного ratio-сэмплирования по traceId.
 * <p>
 * Закрепляют точную семантику {@link TraceIdRatioDecision}: алгоритм совместим с OpenTelemetry
 * {@code TraceIdRatioBased} (нижние 64 бита traceId, сравнение {@code Math.abs(randomPart) < p*Long.MAX_VALUE}).
 * Ожидаемые значения вычислены вручную по фиксированным traceId, поэтому тест защищает смысл,
 * а не случайную форму реализации.
 */
class TraceIdRatioDecisionTest {

    private static final String UPPER = "0000000000000000";

    @Test
    void probabilityOne_alwaysSamples_evenForNullTraceId() {
        assertThat(TraceIdRatioDecision.shouldSample(null, 1.0)).isTrue();
        assertThat(TraceIdRatioDecision.shouldSample(UPPER + "7fffffffffffffff", 1.0)).isTrue();
    }

    @Test
    void probabilityZero_neverSamples() {
        assertThat(TraceIdRatioDecision.shouldSample(UPPER + "0000000000000001", 0.0)).isFalse();
        assertThat(TraceIdRatioDecision.shouldSample(null, 0.0)).isFalse();
    }

    @Test
    void nullTraceId_withFractionalProbability_isFalse() {
        assertThat(TraceIdRatioDecision.shouldSample(null, 0.5)).isFalse();
    }

    @Test
    void shortTraceId_lessThan32Chars_isFalse() {
        // 31 символ — короче требуемых 32; функция возвращает false без NumberFormatException.
        String shortId = "0000000000000000000000000000001";
        assertThat(shortId.length()).isEqualTo(31);
        assertThat(TraceIdRatioDecision.shouldSample(shortId, 0.5)).isFalse();
    }

    @Test
    void smallRandomPart_belowBound_samplesAtHalf() {
        // Нижние 64 бита = 1; abs(1) < 0.5 * Long.MAX_VALUE -> true.
        assertThat(TraceIdRatioDecision.shouldSample(UPPER + "0000000000000001", 0.5)).isTrue();
    }

    @Test
    void largeRandomPart_aboveBound_dropsAtHalf() {
        // Нижние 64 бита = Long.MAX_VALUE; abs(Long.MAX_VALUE) >= 0.5 * Long.MAX_VALUE -> false.
        assertThat(TraceIdRatioDecision.shouldSample(UPPER + "7fffffffffffffff", 0.5)).isFalse();
    }

    @Test
    void deterministic_sameInputsSameResult() {
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        boolean first = TraceIdRatioDecision.shouldSample(traceId, 0.25);
        boolean second = TraceIdRatioDecision.shouldSample(traceId, 0.25);
        assertThat(first).isEqualTo(second);
    }
}
