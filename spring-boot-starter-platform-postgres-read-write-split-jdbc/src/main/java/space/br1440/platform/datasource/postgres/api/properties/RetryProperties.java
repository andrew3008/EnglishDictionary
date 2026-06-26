package space.br1440.platform.datasource.postgres.api.properties;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Параметры retry-семантики {@code PasswordExpiryRetryDataSourceProxy}.
 */
public record RetryProperties(
        @DefaultValue("true") boolean invalidPasswordRetryEnabled
) {
}
