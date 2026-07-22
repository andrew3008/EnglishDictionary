package space.br1440.platform.tracing.otel.extension.processor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.tracing.core.runtime.versioned.VersionedStateHolder;
import space.br1440.platform.tracing.core.validation.ValidationPolicyUpdate;
import space.br1440.platform.tracing.core.validation.ValidationSnapshot;
import space.br1440.platform.tracing.otel.extension.safety.RateLimitedLogger;

import java.util.function.UnaryOperator;

/**
 * Agent-side holder runtime-политики валидации (Фаза 14 / PR-8A): живёт рядом со своим consumer'ом
 * ({@link ValidatingSpanProcessor}) и переиспользует общий примитив {@link VersionedStateHolder}
 * (CAS + last-known-good).
 */
public final class ValidationPolicyHolder {

    private static final Logger log = LoggerFactory.getLogger(ValidationPolicyHolder.class);

    private final VersionedStateHolder<ValidationSnapshot> holder;
    private final boolean strictRuntimeAllowed;
    private final RateLimitedLogger rateLimitedLog = new RateLimitedLogger(log);

    public ValidationPolicyHolder(ValidationSnapshot initial) {
        this(initial, false);
    }

    public ValidationPolicyHolder(ValidationSnapshot initial, boolean strictRuntimeAllowed) {
        this.holder = new VersionedStateHolder<>(initial);
        this.strictRuntimeAllowed = strictRuntimeAllowed;
    }

    /** Startup-only flag: whether runtime updates may enable {@code strict=true} (PR-9F). */
    public boolean isStrictRuntimeAllowed() {
        return strictRuntimeAllowed;
    }

    public ValidationSnapshot current() {
        return holder.current();
    }

    public long version() {
        return holder.version();
    }

    /**
     * Атомарно публикует полную политику валидации (PR-8A): build next snapshot → CAS.
     *
     * @return {@code true} if published; {@code false} if last-known-good retained
     */
    public boolean tryApplyPolicyUpdate(boolean enabled, boolean strict, String source) {
        if (strict && !strictRuntimeAllowed) {
            rateLimitedLog.warn(
                    "ValidationPolicyHolder: runtime strict mode rejected (strictRuntimeAllowed=false); "
                            + "last-known-good retained (version={})",
                    holder.version());
            return false;
        }
        return tryUpdate(prev -> ValidationPolicyUpdate.buildNext(prev, enabled, strict, source));
    }

    /**
     * Атомарно строит новый снимок из предыдущего и публикует через CAS. {@code builder} обязан
     * быть side-effect-free.
     *
     * @return {@code true}, если применён; {@code false} — сохранён last-known-good
     */
    public boolean tryUpdate(UnaryOperator<ValidationSnapshot> builder) {
        boolean applied = holder.tryUpdate(builder);
        if (!applied) {
            rateLimitedLog.warn("ValidationPolicyHolder: невалидная конфигурация — сохранён last-known-good (version={})",
                    holder.version());
        }
        return applied;
    }
}
