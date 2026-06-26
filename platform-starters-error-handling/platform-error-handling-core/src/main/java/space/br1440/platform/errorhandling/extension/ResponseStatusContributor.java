package space.br1440.platform.errorhandling.extension;

import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;

import java.util.Optional;

/**
 * SPI для резолвинга HTTP-статуса по одному узлу {@link Throwable}.
 *
 * <p>Реализации регистрируются как Spring-бины и опрашиваются
 * {@link space.br1440.platform.errorhandling.core.resolver.ResponseStatusResolverBean} в порядке возрастания
 * {@link Ordered#getOrder()} (сортировка через {@link org.springframework.core.annotation.AnnotationAwareOrderComparator}).
 * Побеждает <em>первый</em> непустой {@link Optional} (цепочка ответственности).
 *
 * <p>Для явного приоритета в оркестраторе переопределяйте {@link #getOrder()} — так порядок виден
 * в одном месте с логикой contributor'а и не путается с аннотацией {@code @Order} на Spring-бине.
 *
 * <p>Contributor получает <strong>по одному узлу цепочки причин</strong> за вызов; обход
 * цепочки — только зона ответственности оркестратора
 * ({@link space.br1440.platform.errorhandling.core.resolver.ResponseStatusResolverBean}).
 * Самим contributor'ам вызывать {@link Throwable#getCause()} <em>нельзя</em>.
 *
 * <h3>Пример расширения в микросервисе</h3>
 * <pre>{@code
 * @Component
 * public class PaymentExceptionContributor implements ResponseStatusContributor {
 *
 *     @Override
 *     public int getOrder() {
 *         return Ordered.HIGHEST_PRECEDENCE;
 *     }
 *
 *     @Override
 *     public Optional<HttpStatusResolution> resolve(@NonNull Throwable throwable) {
 *         if (throwable instanceof InsufficientFundsException e) {
 *             return Optional.of(new HttpStatusResolution(
 *                     HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds"));
 *         }
 *         return Optional.empty();
 *     }
 * }
 * }</pre>
 *
 * <h3>Встроенные contributor'ы (регистрирует стартер)</h3>
 * <ul>
 *   <li>{@code SpringResponseStatusExceptionContributor} — приоритет
 *       {@value space.br1440.platform.errorhandling.core.strategy.cause.http.SpringResponseStatusExceptionContributor#ORDER}
 *       через {@link #getOrder()}; обрабатывает {@code ResponseStatusException} / {@code ErrorResponse} без reflection</li>
 *   <li>{@code AnnotationResponseStatusContributor} — приоритет
 *       {@value space.br1440.platform.errorhandling.core.strategy.cause.http.AnnotationResponseStatusContributor#ORDER}
 *       через {@link #getOrder()}; читает {@code @ResponseStatus} через
 *       {@code AnnotatedElementUtils.findMergedAnnotation}</li>
 * </ul>
 *
 * @see space.br1440.platform.errorhandling.core.resolver.ResponseStatusResolverBean
 * @see HttpStatusResolution
 */
public interface ResponseStatusContributor extends Ordered {

    /**
     * Резолвит HTTP-статус для переданного узла исключения.
     */
    @NonNull
    Optional<HttpStatusResolution> resolve(@NonNull Throwable throwable);

    /**
     * Позиция в порядке опроса. Меньше значение — выше приоритет.
     * По умолчанию — {@link Ordered#LOWEST_PRECEDENCE}.
     */
    @Override
    default int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

}
