package space.br1440.platform.tracing.otel.extension.sampler;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Last-known-good контракт {@link SamplerStateHolder}: невалидное обновление не затирает рабочую
 * конфигурацию.
 */
class SamplerStateHolderTest {

    private SamplerStateHolder holder() {
        return new SamplerStateHolder(true, List.of(), List.of("on"), Map.of(), 0.5);
    }

    @Test
    void tryUpdate_с_невалидным_снимком_сохраняет_last_known_good() {
        SamplerStateHolder holder = holder();
        long versionBefore = holder.current().version();
        double ratioBefore = holder.current().defaultRatio();

        // Невалидный defaultRatio отклоняется в SamplingPolicySnapshotFactory до создания снимка.
        boolean applied = holder.tryUpdate(() -> new SamplerState(
                true, List.of(), Set.of("on"), Map.of(), 5.0, versionBefore + 1, Instant.now(), "test"));

        assertThat(applied).isFalse();
        assertThat(holder.current().version()).isEqualTo(versionBefore);
        assertThat(holder.current().defaultRatio()).isEqualTo(ratioBefore);
    }

    @Test
    void tryUpdate_с_валидным_снимком_применяется() {
        SamplerStateHolder holder = holder();
        long versionBefore = holder.current().version();

        boolean applied = holder.tryUpdate(() -> new SamplerState(
                true, List.of(), Set.of("on"), Map.of(), 0.9, versionBefore + 1, Instant.now(), "test"));

        assertThat(applied).isTrue();
        assertThat(holder.current().defaultRatio()).isEqualTo(0.9);
    }

    @Test
    void startup_rejectsInvalidDefaultRatio() {
        assertThatThrownBy(() -> new SamplerStateHolder(
                true, List.of(), List.of("on"), Map.of(), 1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultRatio");
    }

    @Test
    void update_null_игнорируется() {
        SamplerStateHolder holder = holder();
        SamplerState before = holder.current();
        holder.update(null);
        assertThat(holder.current()).isSameAs(before);
    }
}
