package space.br1440.platform.errorhandling.http;

import lombok.experimental.UtilityClass;
import org.springframework.http.MediaType;

import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

/**
 * Константы медиатипов для ответов RFC 9457 Problem Details.
 * <p>
 * Описывает медиатипы, используемые при сериализации ошибок REST API
 * согласно стандарту <a href="https://www.rfc-editor.org/rfc/rfc9457">RFC 9457</a>.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9457">RFC 9457 — Problem Details for HTTP APIs</a>
 */
@UtilityClass
public final class ErrorMediaTypes {

    public static final String MODEL_VERSION = ";version=1";

    public static final String ERROR_RESPONSE_MEDIA_TYPE_STRING = APPLICATION_PROBLEM_JSON_VALUE + MODEL_VERSION;
    public static final MediaType ERROR_RESPONSE_MEDIA_TYPE = MediaType.parseMediaType(ERROR_RESPONSE_MEDIA_TYPE_STRING);

}