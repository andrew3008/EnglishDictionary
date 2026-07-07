package space.br1440.platform.tracing.core.sampling.model;

import java.util.List;
import java.util.Set;

/**
 * Тестовые фикстуры для {@link SamplingPolicySnapshot}.
 * <p>
 * Заменяет удалённый из production API 3-аргументный конструктор-удобство: собирает снимок с
 * дефолтами (пустые force-values и route-ratios, {@code defaultRatio=1.0}) через полный
 * 5-аргументный конструктор. Живёт только в test sources — production surface остаётся минимальной.
 */
public final class SamplingPolicySnapshotFixtures {

    private SamplingPolicySnapshotFixtures() {
    }

    /** Снимок с дефолтами: {@code forceRecordValues=∅}, {@code routeRatios=∅}, {@code defaultRatio=1.0}. */
    public static SamplingPolicySnapshot snapshot(boolean enabled, List<String> droppedRoutes) {
        return new SamplingPolicySnapshot(enabled, droppedRoutes, Set.of(), List.of(), 1.0);
    }
}
