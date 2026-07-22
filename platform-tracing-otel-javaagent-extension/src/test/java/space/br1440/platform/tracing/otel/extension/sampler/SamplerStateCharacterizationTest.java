package space.br1440.platform.tracing.otel.extension.sampler;

import io.opentelemetry.api.trace.SpanKind;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;
import space.br1440.platform.tracing.test.assertions.SamplingAssertions;
import space.br1440.platform.tracing.test.harness.SamplerHarness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization изменений runtime-состояния {@link SamplerStateHolder} (PR-5A).
 */
class SamplerStateCharacterizationTest {

    @Test
    void обновление_default_ratio_меняет_последующее_решение() {
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of("on"), Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(holder);

        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        .sample())
                .isRecordAndSample();

        long versionBefore = holder.version();
        holder.update(new SamplerState(
                true, List.of(), Set.of("on"), Map.of(), 0.0,
                versionBefore + 1, Instant.now(), "characterization"));

        var dropResult = SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .sample();
        SamplerDecisionAssert.assertThat(dropResult).isDrop();
        SamplingAssertions.assertSamplingReason(dropResult, PlatformSamplingReasons.GLOBAL_RATIO_DROP);
        assertThat(holder.current().defaultRatio()).isEqualTo(0.0);
        assertThat(holder.version()).isGreaterThan(versionBefore);
    }

    @Test
    void kill_switch_через_update_отменяет_глобальный_ratio_1() {
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of("on"), Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(holder);

        long versionBefore = holder.version();
        holder.update(new SamplerState(
                false, List.of(), Set.of("on"), Map.of(), 1.0,
                versionBefore + 1, Instant.now(), "characterization"));

        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler).sample()).isDrop();
    }

    @Test
    void добавление_drop_path_через_update_влияет_на_следующий_запрос() {
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of("on"), Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(holder);

        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .putStringAttribute("url.path", "/actuator/health")
                        .sample())
                .isRecordAndSample();

        long versionBefore = holder.version();
        holder.update(new SamplerState(
                true, List.of("/actuator"), Set.of("on"), Map.of(), 1.0,
                versionBefore + 1, Instant.now(), "characterization"));

        var result = SamplerHarness.of(sampler)
                .putStringAttribute("url.path", "/actuator/health")
                .sample();
        SamplerDecisionAssert.assertThat(result).isDrop();
        SamplingAssertions.assertSamplingReason(result, PlatformSamplingReasons.DROP_PATH);
    }
}
