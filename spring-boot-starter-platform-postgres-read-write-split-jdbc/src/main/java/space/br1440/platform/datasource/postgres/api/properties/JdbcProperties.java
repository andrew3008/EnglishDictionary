package space.br1440.platform.datasource.postgres.api.properties;

import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Driver-параметры pgJDBC, применяемые к обоим пулам (write/read).
 *
 * <p>Заполняются из {@code PG_CONNECTION_PROPERTIES} (semicolon-DSL парсится один раз на bootstrap-границе)
 * либо из {@code application.yml} как nested map {@code jdbc.properties.<key>}.
 *
 * @param properties driver-параметры (camelCase ключи, как ожидает pgJDBC)
 */
public record JdbcProperties(
        @DefaultValue Map<String, String> properties
) {

    public JdbcProperties {
        properties = (properties == null) ? Map.of() : new LinkedHashMap<>(properties);
    }
}
