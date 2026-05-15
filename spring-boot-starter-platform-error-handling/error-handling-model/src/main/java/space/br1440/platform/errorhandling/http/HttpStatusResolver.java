package space.br1440.platform.errorhandling.http;

import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.exception.AbstractErrorResponseException;

/**
 * Стратегия резолвинга HTTP-статуса по типу исключения и HTTP-маркерам (трёхосная модель).
 *
 * <p>Платформа регистрирует {@link DefaultHttpStatusResolver} через
 * {@code @ConditionalOnMissingBean}. Микросервис может переопределить поведение,
 * зарегистрировав собственный бин и делегировав платформенному для необработанных случаев:
 *
 * <pre>{@code
 * @Bean
 * public HttpStatusResolver httpStatusResolver(DefaultHttpStatusResolver platformResolver) {
 *     return exception -> {
 *         if (exception instanceof MyDomainException) {
 *             return HttpStatus.UNPROCESSABLE_ENTITY;
 *         }
 *
 *         return platformResolver.resolve(exception);
 *     };
 * }
 * }</pre>
 */
public interface HttpStatusResolver {

    HttpStatus resolve(AbstractErrorResponseException exception);

}
