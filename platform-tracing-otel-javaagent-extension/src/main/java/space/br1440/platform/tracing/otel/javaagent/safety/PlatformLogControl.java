package space.br1440.platform.tracing.otel.javaagent.safety;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyUpdateOutcome;

/**
 * Runtime-контроль уровня диагностического логирования платформы в classloader'е Java Agent
 * extension (Фаза 14).
 * <p>
 * <b>Проблема.</b> Логгеры приложения (core/autoconfigure) живут в application classloader и
 * управляются на лету через Spring Boot {@code /actuator/loggers}. Логгеры расширения работают
 * в bootstrap/agent classloader, куда actuator не достаёт. Чтобы во время инцидента можно было
 * приглушить или, наоборот, поднять детализацию диагностики платформы без рестарта, уровень
 * хранится здесь и проверяется единым диагностическим каналом ({@link RateLimitedLogger}).
 * <p>
 * Это политика (verbosity), а не топология. Управляется через JMX-сеттер; смена уровня сама по
 * себе логируется rate-limited, чтобы переключатель нельзя было использовать как источник шума.
 *
 * <h2>Почему shared-singleton</h2>
 * Тот же приём, что и {@link TracingDiagnostics#shared()}: в агенте один classloader, поэтому
 * один процессный инстанс корректно охватывает все диагностические логгеры расширения. В тестах
 * можно создать изолированный экземпляр через {@code new PlatformLogControl()}.
 *
 * <p><b>Потокобезопасность:</b> уровень хранится в {@link AtomicReference}.
 */
public final class PlatformLogControl {

    /**
     * Уровни в порядке возрастания детализации. Числовой {@link #severity} задаёт порог:
     * сообщение уровня {@code X} печатается, если {@code currentLevel.severity >= X.severity}.
     */
    public enum LogLevel {
        OFF(0),
        ERROR(1),
        WARN(2),
        INFO(3),
        DEBUG(4),
        TRACE(5);

        private final int severity;

        LogLevel(int severity) {
            this.severity = severity;
        }

        public int severity() {
            return severity;
        }
    }

    private static final PlatformLogControl SHARED = new PlatformLogControl();

    /** По умолчанию INFO: видны и WARN-диагностика, и однократные recovery-сообщения. */
    private final AtomicReference<AcceptedPolicyAccess> acceptedPolicy =
            new AtomicReference<>(AcceptedPolicyAccess.isolatedDefaults());

    public static PlatformLogControl shared() {
        return SHARED;
    }

    public LogLevel getLevel() {
        return LogLevel.valueOf(acceptedPolicy.get().current().diagnosticsLogLevel());
    }

    public void setLevel(LogLevel newLevel) {
        if (newLevel != null) {
            acceptedPolicy.get().updateDiagnosticsLogLevel(newLevel.name());
        }
    }

    /**
     * Парсит уровень из строки (регистронезависимо). {@code null}/неизвестное значение — игнор
     * (LKG: текущий уровень сохраняется), чтобы кривой ввод через JMX не сломал диагностику.
     *
     * @return {@code true}, если уровень распознан и применён
     */
    public boolean setLevel(String raw) {
        if (raw == null) {
            return false;
        }
        AcceptedPolicyUpdateOutcome outcome =
                acceptedPolicy.get().updateDiagnosticsLogLevel(raw.trim().toUpperCase(Locale.ROOT));
        return outcome == AcceptedPolicyUpdateOutcome.APPLIED
                || outcome == AcceptedPolicyUpdateOutcome.UNCHANGED;
    }

    public void bindAcceptedPolicy(AcceptedPolicyAccess policy) {
        acceptedPolicy.set(Objects.requireNonNull(policy, "policy"));
    }

    /** @return {@code true}, если сообщение указанного уровня должно печататься при текущем пороге. */
    public boolean isEnabled(LogLevel messageLevel) {
        return messageLevel != LogLevel.OFF && getLevel().severity() >= messageLevel.severity();
    }
}
