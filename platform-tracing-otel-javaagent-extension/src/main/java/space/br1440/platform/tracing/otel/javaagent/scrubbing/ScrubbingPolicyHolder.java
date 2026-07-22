package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.tracing.core.runtime.versioned.VersionedStateHolder;
import space.br1440.platform.tracing.otel.javaagent.safety.RateLimitedLogger;

import java.util.function.UnaryOperator;

/**
 * Agent-side holder runtime-политики scrubbing'а (Фаза 14 / PR-7A–7B): живёт рядом со своим consumer'ом
 * ({@code ScrubbingSpanProcessor}) в classloader'е OTel Agent extension и переиспользует общий
 * примитив {@link VersionedStateHolder} (CAS + last-known-good).
 * <p>
 * Невалидный апдейт (битый regex, превышение лимитов) не затирает рабочую политику; логирование
 * выполняется после возврата {@code tryUpdate} (CAS-функция side-effect-free).
 */
public final class ScrubbingPolicyHolder {

    private static final Logger log = LoggerFactory.getLogger(ScrubbingPolicyHolder.class);

    private final VersionedStateHolder<ScrubbingSnapshot> holder;
    private final RateLimitedLogger rateLimitedLog = new RateLimitedLogger(log);

    public ScrubbingPolicyHolder(ScrubbingSnapshot initial) {
        this.holder = new VersionedStateHolder<>(initial);
    }

    public ScrubbingSnapshot current() {
        return holder.current();
    }

    public long version() {
        return holder.version();
    }

    /**
     * Validates merged scrubbing policy domain before CAS publish (throws on invalid input).
     */
    public void validatePolicyUpdateDomain(String[] ruleNames) {
        ScrubbingPolicyUpdate.validateDomain(ruleNames);
    }

    /**
     * Атомарно публикует полную политику scrubbing'а (PR-7B): validate merged snapshot → compile → CAS.
     *
     * @param ruleNames {@code null} — сохранить текущие скомпилированные правила (toggle {@code enabled} only)
     * @return {@code true} if published; {@code false} if last-known-good retained
     */
    public boolean tryApplyPolicyUpdate(boolean enabled, String[] ruleNames, String source) {
        try {
            ScrubbingPolicyUpdate.validateDomain(ruleNames);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return tryUpdate(prev -> ScrubbingPolicyUpdate.buildNext(prev, enabled, ruleNames, source));
    }

    /**
     * Атомарно строит новый снимок из предыдущего и публикует через CAS. {@code builder} обязан
     * быть side-effect-free (может вызываться несколько раз при contention).
     *
     * @return {@code true}, если применён; {@code false} — сохранён last-known-good
     */
    public boolean tryUpdate(UnaryOperator<ScrubbingSnapshot> builder) {
        boolean applied = holder.tryUpdate(builder);
        if (!applied) {
            rateLimitedLog.warn("ScrubbingPolicyHolder: невалидная конфигурация — сохранён last-known-good (version={})",
                    holder.version());
        }
        return applied;
    }
}
