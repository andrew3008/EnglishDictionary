package space.br1440.platform.errorhandling.core.strategy.cause.http;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ResponseStatusException;
import space.br1440.platform.errorhandling.extension.HttpStatusResolution;
import space.br1440.platform.errorhandling.extension.ResponseStatusContributor;

import java.util.Optional;

/**
 * Встроенный {@link ResponseStatusContributor}: обрабатывает Spring
 * {@link ResponseStatusException} и контракт {@link ErrorResponse} без reflection.
 *
 * <p>Порядок опроса в {@link space.br1440.platform.errorhandling.core.resolver.ResponseStatusResolverBean}
 * задаётся через {@link #getOrder()} (= {@value #ORDER}) — выше приоритета, чем у
 * {@link AnnotationResponseStatusContributor} (100), потому что у
 * {@code ResponseStatusException} явный программный статус, который должен иметь
 * приоритет над возможной унаследованной аннотацией {@code @ResponseStatus}.
 *
 * <p>Покрывает и классический {@link ResponseStatusException} (Spring MVC / WebFlux),
 * и интерфейс {@link ErrorResponse} (Spring 6 / Spring Boot 3).
 */
public class SpringResponseStatusExceptionContributor implements ResponseStatusContributor {

    /** Порядок опроса — перед {@link AnnotationResponseStatusContributor}. */
    public static final int ORDER = 50;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @NonNull
    @Override
    public Optional<HttpStatusResolution> resolve(@NonNull Throwable throwable) {
        if (throwable instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.resolve(errorResponse.getStatusCode().value());
            if (status == null) {
                return Optional.empty();
            }

            String reason = extractReason(errorResponse, status);
            return Optional.of(new HttpStatusResolution(status, reason));
        }

        return Optional.empty();
    }

    @NonNull
    private static String extractReason(@NonNull ErrorResponse response,
                                        @NonNull HttpStatus fallback) {
        if (response instanceof ResponseStatusException rse) {
            String reason = rse.getReason();
            if (reason != null && !reason.isBlank()) {
                return reason;
            }
        }

        return fallback.getReasonPhrase();
    }
}
