package space.br1440.platform.errorhandling.extension;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.extension.order.TopExceptionStrategyOrder;
import space.br1440.platform.errorhandling.model.ErrorEntry;

import static space.br1440.platform.errorhandling.extension.LogPolicy.*;

/**
 * SPI для классификации и обработки исключений верхнего уровня.
 *
 * <p>Реализации регистрируются как Spring-бины и вызываются оркестратором
 * ({@code GlobalExceptionHandler} в MVC-транспорте, аналог — в WebFlux/gRPC)
 * в порядке возрастания {@link #getOrder()} до тех пор, пока первая из них
 * не заявит поддержку через {@link #supports(Exception)}.
 *
 * <h3>Жизненный цикл вызова</h3>
 * <ol>
 *   <li>Оркестратор перехватывает {@link Exception} и перебирает стратегии.</li>
 *   <li>Для каждой стратегии вызывается {@link #supports(Exception)}.</li>
 *   <li>Первая стратегия, вернувшая {@code true}, формирует {@link ErrorEntry}
 *       через {@link #handle(Exception, ErrorHandlingContext)}.</li>
 *   <li>Результат обогащается {@link ErrorEntryEnricher enricher'ами}, логируется
 *       согласно {@link #logPolicy(Exception, ErrorEntry)} и возвращается клиенту.</li>
 *   <li>Если ни одна стратегия не подошла — вызывается {@link UnknownExceptionHandler}.</li>
 * </ol>
 *
 * <h3>Type-indexed кэш</h3>
 * <p>Оркестратор поддерживает кэш вида {@code Class<? extends Exception> → Strategy}.
 * После первого успешного матча стратегия запоминается по конкретному классу исключения,
 * и последующие запросы с тем же классом обходят линейный перебор за {@code O(1)}.
 */
public interface TopExceptionHandlingStrategy extends Ordered {

    /**
     * Приоритет стратегии в цепочке обработки.
     * Чем меньше значение, тем выше приоритет.
     *
     * @see TopExceptionStrategyOrder
     */
    @Override
    int getOrder();

    /**
     * Политика логирования для данного исключения.
     *
     * <p>Реализация по умолчанию выводит политику из {@code httpCode} в {@link ErrorEntry}:
     * 5xx → {@link LogPolicy#ERROR},
     * 4xx → {@link LogPolicy#WARN},
     * прочие → {@link LogPolicy#DEBUG}.
     *
     * <p>Стратегии с более тонкими требованиями переопределяют этот метод.
     */
    @NonNull
    default LogPolicy logPolicy(@NonNull Exception exception, @NonNull ErrorEntry entry) {
        HttpStatus httpStatus = entry.getHttpStatus();
        if (httpStatus.is5xxServerError()) {
            return ERROR;
        }

        if (httpStatus.is4xxClientError()) {
            return WARN;
        }

        return DEBUG;
    }

    /**
     * Возвращает {@code true}, если данная стратегия умеет обработать указанное исключение.
    */
    boolean supports(@NonNull Exception exception);

    /**
     * Формирует {@link ErrorEntry} для поддерживаемого исключения.
     */
    @NonNull
    ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext context);

}
