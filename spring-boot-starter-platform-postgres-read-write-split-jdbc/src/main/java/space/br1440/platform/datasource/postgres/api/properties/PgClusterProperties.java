package space.br1440.platform.datasource.postgres.api.properties;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Типизированная immutable модель свойств подключения к PostgreSQL-кластеру.
 *
 * <h2>Контракт активации</h2>
 * <p>Стартер активен только при {@code datasource-routing-mode = CLIENT} (см. {@code OnClientDataSourceRoutingCondition}).
 * Источник значения не важен: {@code PG_ROUTING_MODE} env (мапится EPP), {@code application.yml}, system property.
 *
 * <h2>Источники свойств (от низшего к высшему)</h2>
 * <ol>
 *   <li>EPP {@code postgresClusterEnvDefaults} — {@code PG_*} env vars, lowest precedence;</li>
 *   <li>{@code application.yml} потребителя;</li>
 *   <li>OS environment / system properties (relaxed binding), highest.</li>
 * </ol>
 *
 * <p>Семантическая валидация (required {@code database}/{@code nodes} в CLIENT mode,
 * passwordless pair rule) выполняется {@code PgClusterPropertiesValidator}, а не JSR-303
 * на record — потому что обязательность зависит от {@code datasource-routing-mode}.
 *
 * @param datasourceRoutingMode где принимается routing-решение (gate активации)
 * @param nodes                 узлы кластера {@code host:port}
 * @param database              имя БД
 * @param credentials           учётные данные (env-based)
 * @param jdbc                  driver-параметры pgJDBC
 * @param read                  параметры read-пула
 * @param pool                  настройки HikariCP-пулов
 * @param retry                 retry-семантика на 28P01
 */
@ConfigurationProperties(prefix = PgClusterProperties.PREFIX)
public record PgClusterProperties(
        DataSourceRoutingMode datasourceRoutingMode,
        @DefaultValue List<PgNode> nodes,
        String database,
        @DefaultValue Credentials credentials,
        @NestedConfigurationProperty @DefaultValue JdbcProperties jdbc,
        @NestedConfigurationProperty @DefaultValue ReadReplicaProperties read,
        @NestedConfigurationProperty @DefaultValue PoolGroupProperties pool,
        @NestedConfigurationProperty @DefaultValue RetryProperties retry
) {

    public static final String PREFIX = "platform.datasource.postgres-cluster";

    public PgClusterProperties {
        nodes = (nodes == null) ? List.of() : List.copyOf(nodes);
        credentials = (credentials == null) ? new Credentials(null, null) : credentials;
        jdbc = (jdbc == null) ? new JdbcProperties(null) : jdbc;
        read = (read == null) ? new ReadReplicaProperties(null) : read;
        pool = (pool == null) ? new PoolGroupProperties(null, null) : pool;
        retry = (retry == null) ? new RetryProperties(true) : retry;
    }

    /**
     * Учётные данные для подключения. Допускают {@code null}/blank (passwordless / trust-auth local mode).
     * Полупара (один из двух задан) отвергается {@code PgClusterPropertiesValidator}.
     *
     * @param username имя пользователя
     * @param password пароль (из K8s Secret через Vault Agent)
     */
    public record Credentials(
            String username,
            @JsonIgnore String password
    ) {
    }
}
