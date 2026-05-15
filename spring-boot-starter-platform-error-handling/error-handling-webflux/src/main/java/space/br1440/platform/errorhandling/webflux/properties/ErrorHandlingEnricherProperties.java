package space.br1440.platform.errorhandling.webflux.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки выполнения цепочки {@code ErrorEntryEnricher} в WebFlux.
 */
@Getter
@Setter
@Accessors(chain = true)
@Validated
@ConfigurationProperties(prefix = ErrorHandlingEnricherProperties.PREFIX)
public class ErrorHandlingEnricherProperties {

    public static final String PREFIX = "platform.error-handling.enricher";

    public static final long DEFAULT_TIMEOUT_MS = 100L;

    /**
     * Таймаут (мс) на выполнение одного {@code ErrorEntryEnricher} в реактивной цепочке.
     * Границы задаются {@code @Min}/{@code @Max}; при биндинге из конфигурации с
     * {@code @Validated} некорректное значение приводит к ошибке старта контекста.
     *
     * <pre>
     * platform:
     *   error-handling:
     *     enricher:
     *       timeout-ms: 100
     * </pre>
     */
    @Min(value = 10, message = "enricher.timeoutMs must be >= 10")
    @Max(value = 10_000, message = "enricher.timeoutMs must be <= 10000")
    private long timeoutMs = DEFAULT_TIMEOUT_MS;

}
