package space.br1440.platform.datasource.postgres.api.properties;

import org.apache.commons.lang3.StringUtils;

/**
 * Узел PostgreSQL-кластера: пара {@code host:port}.
 *
 * <p>Поддерживаются IPv4, hostname, FQDN. Порт валидируется в диапазоне 1..65535.
 */
public record PgNode(String host, int port) {

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65_535;

    public PgNode {
        if (StringUtils.isBlank(host)) {
            throw new IllegalArgumentException("PgNode host must not be blank");
        }

        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException("PgNode port out of range [" + MIN_PORT + ".." + MAX_PORT + "]: " + port);
        }

        host = host.strip();
    }

    /** Каноническое строковое представление {@code host:port} для multi-host JDBC URL. */
    public String toHostPort() {
        return host + ":" + port;
    }

}
