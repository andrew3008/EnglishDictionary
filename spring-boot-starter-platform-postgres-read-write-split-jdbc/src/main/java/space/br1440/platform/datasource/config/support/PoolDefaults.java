package space.br1440.platform.datasource.config.support;

import com.zaxxer.hikari.HikariConfig;

/**
 * Защищённые умолчания для HikariCP-пулов маршрутизатора.
 *
 * <p>Класс без состояния, все методы статические. Инстанциирование запрещено.
 *
 * <h2>Write-пул</h2>
 * <ul>
 *   <li>{@code connectionTestQuery} — {@code SELECT CASE WHEN NOT pg_is_in_recovery() THEN 1 ELSE random()/0 END}.
 *       Намеренно бросает {@code division by zero} на standby-ноде после Patroni switchover,
 *       что заставляет HikariCP вытолкнуть stale-соединение.</li>
 *   <li>{@code keepaliveTime} управляется на уровне property-класса
 *       (см. {@code HikariRoutingProperties.PoolProperties.keepaliveTime}, дефолт 60 000 мс).
 *       HikariCP начиная с 6.2.x устанавливает свой собственный дефолт {@code 120_000}, поэтому
 *       sentinel-детекция «значение не задано» через {@code keepaliveTime == 0} больше нерабочая,
 *       и применение умолчания вынесено целиком на property-уровень.</li>
 * </ul>
 */
public final class PoolDefaults {

    /**
     * Дефолтный connection-test-query для write-пула.
     * Провоцирует {@code division by zero} на standby-ноде, чтобы HikariCP выбросил stale-соединение.
     */
    public static final String WRITE_CONNECTION_TEST_QUERY =
            "SELECT CASE WHEN NOT pg_is_in_recovery() THEN 1 ELSE random()/0 END";

    private PoolDefaults() {
    }

    /**
     * Применяет защищённые умолчания к write-{@link HikariConfig}: {@code connectionTestQuery}
     * проставляется только если не задан пользователем (значение {@code null} или пустая строка).
     *
     * <p>Параметр {@code keepaliveTime} здесь не трогается — его дефолт заведён на property-уровне
     * ({@code platform.datasource.postgres-cluster.hikari.write.keepalive-time}), что согласовано
     * с поведением HikariCP 6.2+, где конструктор устанавливает собственный дефолт {@code 120_000}.
     */
    public static void applyWriteDefaults(HikariConfig config) {
        String testQuery = config.getConnectionTestQuery();
        if (testQuery == null || testQuery.isEmpty()) {
            config.setConnectionTestQuery(WRITE_CONNECTION_TEST_QUERY);
        }
    }

    /**
     * Устанавливает имя пула только если оно не задано пользователем (null или пусто).
     */
    public static void applyPoolNameIfAbsent(HikariConfig config, String defaultName) {
        String poolName = config.getPoolName();
        if (poolName == null || poolName.isEmpty()) {
            config.setPoolName(defaultName);
        }
    }
}
