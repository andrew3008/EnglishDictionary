package space.br1440.platform.tracing.otel.javaagent.sampler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.tracing.core.runtime.versioned.VersionedStateHolder;
import space.br1440.platform.tracing.otel.javaagent.safety.RateLimitedLogger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Holder для атомарного lock-free обновления конфигурации {@link CompositeSampler}.
 * <p>
 * Фаза 14: переиспользует общий примитив {@link VersionedStateHolder} ({@code core.runtime.versioned},
 * embedded в agent extension jar) по композиции — единый CAS + last-known-good механизм на все домены,
 * без дублирования логики.
 * <p>
 * <b>Last-known-good (Фаза 11):</b> при попытке применить невалидную конфигурацию холдер сохраняет
 * предыдущую валидную и не даёт «плохой» конфигурации затереть рабочую. Логирование/метрики
 * выполняются <b>после</b> возврата {@code tryUpdate} (CAS-функция side-effect-free).
 */
public final class SamplerStateHolder {

    private static final Logger log = LoggerFactory.getLogger(SamplerStateHolder.class);

    private final VersionedStateHolder<SamplerState> holder;
    private final RateLimitedLogger rateLimitedLog = new RateLimitedLogger(log);

    public SamplerStateHolder(boolean enabled, List<String> droppedRoutes, List<String> forceRecordValues, Map<String, Double> routeRatios, double defaultRatio) {
        this.holder = new VersionedStateHolder<>(new SamplerState(
                enabled,
                droppedRoutes,
                forceRecordValues == null ? Set.of() : new java.util.HashSet<>(forceRecordValues),
                routeRatios,
                defaultRatio,
                1,
                Instant.now(),
                "startup"
        ));
    }

    public SamplerState current() {
        return holder.current();
    }

    /** Версия текущего снимка (монотонно растёт через CAS). */
    public long version() {
        return holder.version();
    }

    /**
     * Применяет уже построенный валидный снимок. {@code null} игнорируется (last-known-good
     * сохраняется), чтобы случайный null-апдейт не сбросил рабочую конфигурацию.
     */
    public void update(SamplerState newState) {
        if (newState == null) {
            rateLimitedLog.warn("SamplerStateHolder: попытка update(null) проигнорирована — сохранён last-known-good");
            return;
        }
        holder.tryUpdate(prev -> newState);
    }

    /**
     * Validates merged sampling policy domain before CAS publish (throws on invalid input).
     */
    public void validatePolicyUpdateDomain(
            double defaultRatio,
            String[] droppedRoutes,
            String[] forceRecordValues,
            String[] routeRatioPrefixes,
            double[] routeRatioValues) {
        SamplerPolicyUpdate.validateDomain(
                defaultRatio, droppedRoutes, forceRecordValues, routeRatioPrefixes, routeRatioValues);
    }

    /**
     * Атомарно публикует полную политику сэмплирования (PR-6D): validate merged snapshot → CAS.
     *
     * @return {@code true} if published; {@code false} if last-known-good retained
     */
    public boolean tryApplyPolicyUpdate(
            boolean enabled,
            double defaultRatio,
            String[] droppedRoutes,
            String[] forceRecordValues,
            String[] routeRatioPrefixes,
            double[] routeRatioValues,
            String source) {
        try {
            SamplerPolicyUpdate.validateDomain(
                    defaultRatio, droppedRoutes, forceRecordValues, routeRatioPrefixes, routeRatioValues);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return tryUpdate(prev -> SamplerPolicyUpdate.buildNext(
                prev,
                enabled,
                defaultRatio,
                droppedRoutes,
                forceRecordValues,
                routeRatioPrefixes,
                routeRatioValues,
                source));
    }

    /**
     * Безопасно строит и применяет новый снимок: если построение/валидация бросает исключение
     * (например, {@link IllegalArgumentException} при невалидном {@code defaultRatio} в
     * {@link space.br1440.platform.tracing.core.sampling.properties.SamplingPolicySnapshotFactory}),
     * текущая (last-known-good) конфигурация остаётся без изменений, а сбой логируется
     * rate-limited способом.
     *
     * @param builder поставщик нового {@link SamplerState} (может бросить при невалидных данных);
     *                ОБЯЗАН быть side-effect-free — при contention вызывается несколько раз
     * @return {@code true}, если новый снимок применён; {@code false} — оставлен last-known-good
     */
    public boolean tryUpdate(Supplier<SamplerState> builder) {
        return tryUpdate(prev -> builder.get());
    }

    /**
     * Атомарно строит новый снимок из предыдущего (для инкремента {@code version} и LKG-сравнения)
     * и публикует его через CAS. См. контракт side-effect-free в {@link VersionedStateHolder}.
     *
     * @param builder функция {@code prev -> next} (side-effect-free, обычно ставит
     *                {@code version = prev.version() + 1})
     * @return {@code true}, если применён; {@code false} — сохранён last-known-good
     */
    public boolean tryUpdate(UnaryOperator<SamplerState> builder) {
        boolean applied = holder.tryUpdate(builder);
        if (!applied) {
            rateLimitedLog.warn("SamplerStateHolder: невалидная конфигурация — сохранён last-known-good (version={})",
                    holder.version());
        }
        return applied;
    }
}
