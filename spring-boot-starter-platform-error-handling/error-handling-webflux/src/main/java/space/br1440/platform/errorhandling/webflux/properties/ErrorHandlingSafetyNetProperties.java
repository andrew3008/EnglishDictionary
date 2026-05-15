package space.br1440.platform.errorhandling.webflux.properties;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки safety-net ответа WebFlux ({@code ReactiveGlobalExceptionHandler}).
 */
@Getter
@Setter
@Accessors(chain = true)
@Validated
@ConfigurationProperties(prefix = ErrorHandlingSafetyNetProperties.PREFIX)
public class ErrorHandlingSafetyNetProperties {

    public static final String PREFIX = "platform.error-handling.safety-net";

    /**
     * Включать ли {@code applicationName} в тело safety-net / fatal ответа.
     * По умолчанию {@code false} (CWE-209: утечка внутреннего каталога сервисов).
     *
     * <pre>
     * platform:
     *   error-handling:
     *     safety-net:
     *       expose-application-name: true
     * </pre>
     */
    private boolean exposeApplicationName = false;

}
