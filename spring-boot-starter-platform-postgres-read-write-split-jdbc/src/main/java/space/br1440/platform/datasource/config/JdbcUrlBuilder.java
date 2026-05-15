package space.br1440.platform.datasource.config;

import lombok.experimental.UtilityClass;
import space.br1440.platform.datasource.configuration.support.ReadPoolUrlStrategy;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Сборщик JDBC URL для маршрутизации поверх pgJDBC multi-host формата.
 *
 * <p>Формат: {@code jdbc:postgresql://host1:5432,host2:5432/dbname?targetServerType=primary&...}
 *
 * <p>{@code loadBalanced=true} превращается в driver-property {@code loadBalanceHosts=true}
 * URL query-string'а — в pgJDBC это URL-параметр multi-host failover-режима. Решение
 * передаётся явным флагом, а не наличием ключа в map (исторически было через
 * {@code extraParams}, что давало неявный контракт через side-effect).
 *
 * <p><b>Schema (search_path).</b> Параметр {@code currentSchema} URL-ом не задаётся:
 * управление {@code search_path} — ответственность ORM/SQL-слоя
 * (Hibernate {@code default_schema}, явный {@code SET search_path}, schema-prefix в SQL).
 * Это упрощает контракт стартера и не блокирует потребителей с runtime-сменой схемы.
 */
@UtilityClass
public final class JdbcUrlBuilder {

    /**
     * Допустимый формат pgNodes: {@code host:port} через запятую.
     * Поддерживает IPv4, hostname, FQDN. Порт — 1-5 цифр (диапазон проверяется ниже).
     */
    private static final Pattern NODE_PATTERN = Pattern.compile("^[\\w\\-.]+:\\d{1,5}(,[\\w\\-.]+:\\d{1,5})*$");

    public static String buildUrl(String pgNodes,
                                  String database,
                                  String targetServerType,
                                  boolean loadBalanced,
                                  Map<String, String> extraParams) {
        if (pgNodes == null || pgNodes.isBlank()) {
            throw new IllegalArgumentException("pgNodes must not be blank");
        }

        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("database must not be blank");
        }

        String trimmed = pgNodes.strip();
        if (!NODE_PATTERN.matcher(trimmed).matches()) {
            String message = "Invalid PG_NODES format: '" + trimmed + "'. Expected: host1:port1,host2:port2";
            throw new IllegalArgumentException(message);
        }

        StringBuilder url = new StringBuilder("jdbc:postgresql://")
                .append(trimmed)
                .append("/")
                .append(database)
                .append("?targetServerType=").append(targetServerType);

        if (loadBalanced) {
            url.append("&").append(ReadPoolUrlStrategy.LOAD_BALANCE_HOSTS_KEY).append("=true");
        }

        if (extraParams != null) {
            extraParams.forEach((k, v) -> url.append("&").append(encode(k)).append("=").append(encode(v)));
        }

        return url.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
