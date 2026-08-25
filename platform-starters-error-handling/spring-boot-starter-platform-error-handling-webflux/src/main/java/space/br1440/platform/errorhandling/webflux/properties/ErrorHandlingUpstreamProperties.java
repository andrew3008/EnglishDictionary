package space.br1440.platform.errorhandling.webflux.properties;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки обработки upstream-ответов (WebClient / интеграции WebFlux).
 */
@Getter
@Setter
@Accessors(chain = true)
@Validated
@ConfigurationProperties(prefix = ErrorHandlingUpstreamProperties.PREFIX)
public class ErrorHandlingUpstreamProperties {

    public static final String PREFIX = "platform.error-handling.upstream";

    public static final DataSize DEFAULT_MAX_BODY_BYTES = DataSize.ofKilobytes(4);

    private DataSize maxBodyBytes = DEFAULT_MAX_BODY_BYTES;

    public ErrorHandlingUpstreamProperties setMaxBodyBytes(DataSize maxBodyBytes) {
        this.maxBodyBytes = (maxBodyBytes != null) ? maxBodyBytes : DEFAULT_MAX_BODY_BYTES;
        return this;
    }

}
