package space.br1440.platform.datasource.config;

import com.zaxxer.hikari.HikariConfig;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HikariCP-специфичные свойства CQRS-маршрутизации для JDBC-стартера.
 *
 * <p>Живёт отдельно от {@link PostgresClusterDataSourceProperties} (который в core-модуле),
 * чтобы core не зависел от HikariCP. YAML-префикс:
 * <pre>{@code platform.datasource.postgres-cluster.hikari.{write|read}.*}</pre>
 *
 * <p>Флаг {@code readOnly} управляется автоматически:
 * read-пул всегда создаётся с {@code readOnly=true} в
 * {@link JdbcCqrsRoutingAutoConfiguration}.
 *
 * <p>Параметры {@code connectionTestQuery} и {@code keepaliveTime} для write-пула
 * имеют защищённые умолчания (см. {@code PoolDefaults}) и могут быть переопределены здесь.
 */
@Getter
@Setter
@Accessors(chain = true)
@ConfigurationProperties(prefix = HikariRoutingProperties.PREFIX)
public class HikariRoutingProperties {

    public static final String PREFIX = "platform.datasource.postgres-cluster.hikari";

    /** HikariCP-параметры write-пула (bean name {@code writeDataSource}). */
    private PoolProperties write = new PoolProperties();

    /** HikariCP-параметры read-пула (bean name {@code readDataSource}). */
    private PoolProperties read = new PoolProperties();

    /**
     * HikariCP-параметры для одного пула.
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class PoolProperties {

        private String poolName;

        /** Максимальное число соединений в пуле. По умолчанию: 10. */
        private int maximumPoolSize = 10;

        /** Минимальное число idle-соединений. По умолчанию: 2. */
        private int minimumIdle = 2;

        /** Таймаут ожидания соединения из пула (мс). По умолчанию: 30 000 (30 с). */
        private long connectionTimeout = 30_000;

        /** Максимальное время жизни соединения (мс). По умолчанию: 300 000 (5 мин). */
        private long maxLifetime = 300_000;

        /**
         * Интервал keepalive-проверки idle-соединений (мс). По умолчанию: 60 000 (1 мин).
         * <p>
         * Для write-пула keepalive запускает {@code connectionTestQuery} на idle-соединениях,
         * позволяя обнаруживать stale connections после Patroni switchover без ожидания запроса.
         */
        private long keepaliveTime = 60_000;

        /**
         * SQL-запрос для валидации соединения перед выдачей из пула.
         * <p>
         * Для write-пула по умолчанию используется:
         * {@code SELECT CASE WHEN NOT pg_is_in_recovery() THEN 1 ELSE random()/0 END}.
         * Intentionally вызывает ошибку деления на ноль, если нода стала Standby после switchover,
         * что заставляет HikariCP вытолкнуть stale-соединение.
         */
        private String connectionTestQuery;

        /**
         * Таймаут обнаружения утечки соединения (мс). 0 — отключить. По умолчанию: 0.
         * Рекомендуется включать при отладке: {@code 30000}.
         */
        private long leakDetectionThreshold = 0;

        /**
         * Таймаут инициализации пула (мс). По умолчанию: 1 (HikariCP default — fail-fast).
         * Значение {@code -1} отключает проверку при старте — пул создаётся без попытки подключения.
         */
        private long initializationFailTimeout = 1;

        /**
         * Применяет свойства к {@link HikariConfig}.
         * Вызывается из {@link JdbcCqrsRoutingAutoConfiguration} при создании пула.
         */
        public void applyTo(HikariConfig config) {
            if (poolName != null) {
                config.setPoolName(poolName);
            }

            config.setMaximumPoolSize(maximumPoolSize);
            config.setMinimumIdle(minimumIdle);
            config.setConnectionTimeout(connectionTimeout);
            config.setMaxLifetime(maxLifetime);
            config.setKeepaliveTime(keepaliveTime);
            config.setInitializationFailTimeout(initializationFailTimeout);

            if (connectionTestQuery != null) {
                config.setConnectionTestQuery(connectionTestQuery);
            }

            if (leakDetectionThreshold > 0) {
                config.setLeakDetectionThreshold(leakDetectionThreshold);
            }
        }
    }
}
