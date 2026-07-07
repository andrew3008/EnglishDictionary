package space.br1440.platform.tracing.autoconfigure.jmx;

import java.time.Instant;
import java.util.Set;

/**
 * Снимок результата последнего вызова {@code RuntimeConfigApplier.applyAll()}.
 * Хранит, какие домены были успешно применены, какие упали и метку времени.
 *
 * @param applied   домены, для которых JMX-вызов завершился без исключения
 * @param failed    домены, для которых JMX-вызов выбросил исключение
 * @param timestamp момент завершения apply
 */
public record ConfigApplyResult(
        Set<TracingControlDomain> applied,
        Set<TracingControlDomain> failed,
        Instant timestamp
) {

    /** Все домены успешно применены, ни одного не упало. */
    public boolean isFullSuccess() {
        return failed.isEmpty();
    }

    /** Хотя бы один домен применён, хотя бы один упал. */
    public boolean isPartial() {
        return !applied.isEmpty() && !failed.isEmpty();
    }

    /** Ни один домен не был применён (все упали или были недоступны). */
    public boolean isFullFailure() {
        return applied.isEmpty() && !failed.isEmpty();
    }
}
