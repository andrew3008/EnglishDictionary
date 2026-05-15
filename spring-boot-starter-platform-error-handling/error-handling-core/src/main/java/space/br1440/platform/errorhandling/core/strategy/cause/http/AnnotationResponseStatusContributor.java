package space.br1440.platform.errorhandling.core.strategy.cause.http;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.ResponseStatus;
import space.br1440.platform.errorhandling.extension.HttpStatusResolution;
import space.br1440.platform.errorhandling.extension.ResponseStatusContributor;

import java.util.Optional;

import static org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation;

/**
 * Встроенный {@link ResponseStatusContributor}: резолвит HTTP-статус по аннотации
 * {@link ResponseStatus @ResponseStatus} на классе исключения,
 * включая наследование и мета-аннотации
 * (через {@link org.springframework.core.annotation.AnnotatedElementUtils#findMergedAnnotation}).
 *
 * <p>Порядок опроса в {@link space.br1440.platform.errorhandling.core.resolver.ResponseStatusResolverBean}
 * задаётся через {@link #getOrder()} (= {@value #ORDER}): выполняется после
 * {@link SpringResponseStatusExceptionContributor} (50), который с более высоким приоритетом
 * обрабатывает программный контракт {@code ResponseStatusException} / {@code ErrorResponse}.
 *
 * <p>Рассматривает только <strong>текущий узел</strong>, переданный в {@link #resolve};
 * обход цепочки выполняет исключительно
 * {@link space.br1440.platform.errorhandling.core.resolver.ResponseStatusResolverBean}.
 */
public class AnnotationResponseStatusContributor implements ResponseStatusContributor {

    /** Порядок опроса — после {@link SpringResponseStatusExceptionContributor}. */
    public static final int ORDER = 100;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @NonNull
    @Override
    public Optional<HttpStatusResolution> resolve(@NonNull Throwable throwable) {
        ResponseStatus annotation = findMergedAnnotation(throwable.getClass(), ResponseStatus.class);
        if (annotation == null) {
            return Optional.empty();
        }

        return Optional.of(toHttpStatusResolution(annotation));
    }

    @NonNull
    private static HttpStatusResolution toHttpStatusResolution(@NonNull ResponseStatus annotation) {
        HttpStatus httpStatus = annotation.code();
        String reason = annotation.reason().isBlank()
                ? httpStatus.getReasonPhrase()
                : annotation.reason();

        return new HttpStatusResolution(httpStatus, reason);
    }
}
