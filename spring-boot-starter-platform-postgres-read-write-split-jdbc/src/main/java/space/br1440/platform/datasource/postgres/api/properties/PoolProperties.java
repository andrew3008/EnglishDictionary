package space.br1440.platform.datasource.postgres.api.properties;

import com.zaxxer.hikari.HikariConfig;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки HikariCP-пула.
 *
 * <p>Биндится из {@value PgClusterProperties#PREFIX}.pool.{write,read}.*.
 *
 * <p>{@code minimumIdle} по умолчанию не задан ({@code null}): {@code setMinimumIdle()}
 * не вызывается, и HikariCP остаётся в fixed-size-режиме ({@code minimumIdle == maximumPoolSize}).
 *
 * @param poolName                    имя пула; {@code null} → дефолт по роли (write/read)
 * @param maximumPoolSize             макс. число соединений
 * @param minimumIdle                 мин. число idle-соединений; {@code null} → fixed-size
 * @param connectionTimeout           таймаут ожидания соединения из пула (мс)
 * @param maxLifetime                 макс. время жизни соединения (мс)
 * @param keepaliveTime               интервал keepalive idle-соединений (мс)
 * @param connectionTestQuery         SQL валидации соединения; {@code null} → JDBC4
 * @param leakDetectionThreshold      таймаут обнаружения утечки соединения (мс); 0 — выкл
 * @param initializationFailTimeout   таймаут инициализации пула (мс)
 * @param writerValidationMode        режим валидации write-пула; read-фабрика игнорирует
 */
public record PoolProperties(
        String poolName,
        @DefaultValue("10") int maximumPoolSize,
        Integer minimumIdle,
        @DefaultValue("30000") long connectionTimeout,
        @DefaultValue("300000") long maxLifetime,
        @DefaultValue("60000") long keepaliveTime,
        String connectionTestQuery,
        @DefaultValue("0") long leakDetectionThreshold,
        @DefaultValue("1") long initializationFailTimeout,
        @DefaultValue("PATRONI_PRIMARY_CHECK") WriterValidationMode writerValidationMode
) {

    public static PoolProperties defaults() {
        return new PoolProperties(
                null,
                10,
                null,
                30_000,
                300_000,
                60_000,
                null,
                0,
                1,
                WriterValidationMode.PATRONI_PRIMARY_CHECK
        );
    }

    public void applyTo(HikariConfig config) {
        config.setMaximumPoolSize(maximumPoolSize);

        if (minimumIdle != null) {
            config.setMinimumIdle(minimumIdle);
        }

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
