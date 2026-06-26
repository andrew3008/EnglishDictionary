package space.br1440.platform.errorhandling.extension;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.model.ErrorEntry;

/**
 * SPI для классификации root-cause исключений.
 *
 * <p>Реализации регистрируются как Spring-бины и вызываются {@code CauseChainResolver}
 * в порядке возрастания {@link #getOrder()} до тех пор, пока первая из них
 * не заявит поддержку через {@link #supportsCause(Throwable)}.
 *
 * <p>Порядок задаётся через константы {@link RootCauseExceptionStrategyOrder}.
 */
public interface RootCauseExceptionHandlingStrategy extends Ordered {

    /**
     * Приоритет в порядке классификации первопричины.
     * Чем меньше значение, тем выше приоритет.
     */
    @Override
    int getOrder();

    /**
     * Политика логирования для данного root-cause.
     *
     * <p>Реализация по умолчанию определяет уровень по {@link ErrorEntry#getHttpStatus()}:
     * 5xx &rarr; {@link LogPolicy#ERROR},
     * остальные &rarr; {@link LogPolicy#WARN}.
     */
    @NonNull
    default LogPolicy logPolicy(@NonNull Exception exception,
                                @NonNull ErrorEntry errorEntry) {
        HttpStatus httpStatus = errorEntry.getHttpStatus();
        if (httpStatus.is5xxServerError()) {
            return LogPolicy.ERROR;
        }

        return LogPolicy.WARN;
    }

    /**
     * Возвращает {@code true}, если данная стратегия умеет обработать указанный RootCause Exception.
     */
    boolean supportsCause(@NonNull Throwable cause);

    @NonNull
    RootCauseResolution extractCauseDetails(@NonNull Throwable cause);

}
