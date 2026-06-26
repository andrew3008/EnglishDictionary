package space.br1440.platform.errorhandling.extension;

import org.springframework.lang.NonNull;

import java.util.Objects;

/**
 * Пара: конкретное исключение root-cause и результат его анализа стратегией.
 *
 * <p>Возвращается {@code CauseChainResolver} после успешного нахождения
 * стратегии, способной обработать данный root-cause.
 *
 * @param cause      конкретное исключение, идентифицированное как root-cause
 * @param resolution результат анализа этого исключения стратегией
 * @see RootCauseResolution
 * @see RootCauseExceptionHandlingStrategy
 */
public record ResolvedRootCause(@NonNull Throwable cause,
                                @NonNull RootCauseResolution resolution) {

    public ResolvedRootCause {
        Objects.requireNonNull(cause, "cause must not be null");
        Objects.requireNonNull(resolution, "resolution must not be null");
    }

}
