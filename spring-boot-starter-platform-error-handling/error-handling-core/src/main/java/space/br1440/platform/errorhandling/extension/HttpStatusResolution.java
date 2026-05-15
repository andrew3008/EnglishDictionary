package space.br1440.platform.errorhandling.extension;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;

import java.util.Objects;

/**
 * Value-object, возвращаемый {@link ResponseStatusContributor} после успешного резолвинга HTTP-статуса.
 *
 * <p>Инкапсулирует сам {@link HttpStatus} и человеко-читаемую фразу причины,
 * которая может отличаться от стандартной {@code HttpStatus.getReasonPhrase()}.
 *
 * @param httpStatus   итоговый HTTP-статус
 * @param reasonPhrase текстовое описание причины (например, из {@code @ResponseStatus(reason=...)})
 * @see ResponseStatusContributor
 */
public record HttpStatusResolution(@NonNull HttpStatus httpStatus,
                                   @NonNull String reasonPhrase) {

    public HttpStatusResolution {
        Objects.requireNonNull(httpStatus, "httpStatus must not be null");
        Objects.requireNonNull(reasonPhrase, "reasonPhrase must not be null");
    }

}
