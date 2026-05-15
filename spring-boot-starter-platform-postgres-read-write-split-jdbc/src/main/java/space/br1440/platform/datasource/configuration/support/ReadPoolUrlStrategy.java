package space.br1440.platform.datasource.configuration.support;

import lombok.experimental.UtilityClass;
import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties;
import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties.ReadReplica.Strategy;

/**
 * Маппит {@link Strategy} read-стратегии микросервиса в транспорт-агностичный
 * {@link ReadPoolUrlSpec}: значение {@code targetServerType}, флаг балансировки
 * и набор driver-параметров.
 *
 * <p>Здесь же зафиксированы строковые константы значений {@code targetServerType}
 * pgJDBC/r2dbc-postgresql — в этих именах оба драйвера полностью совместимы,
 * поэтому одна источник правды на оба стартера.
 */
@UtilityClass
public final class ReadPoolUrlStrategy {

    /**
     * Значение {@code targetServerType} для write-пула: соединения принимаются только
     * Primary-нодой кластера. Инвариант CQRS: write-модель пишет только в Primary.
     */
    public static final String WRITE_TARGET_SERVER_TYPE = "primary";

    /**
     * Значение {@code targetServerType} для read-пула при {@link Strategy#SECONDARY_ONLY}:
     * драйвер предпочтёт Secondary, но сможет подключиться к Primary при недоступности
     * реплики (failover-safe чтение для write-heavy сервисов).
     */
    public static final String SECONDARY_ONLY_TARGET_SERVER_TYPE = "preferSecondary";

    /** Значение {@code targetServerType} для режима LOAD_BALANCED: любая доступная нода. */
    public static final String LOAD_BALANCED_TARGET_SERVER_TYPE = "any";

    /**
     * Имя driver-параметра pgJDBC, включающего балансировку соединений между хостами
     * multi-host URL. В R2DBC соответствующее поведение задаётся builder-методом
     * {@code PostgresqlConnectionConfiguration.Builder#loadBalanceHosts(boolean)}
     * — не URL-параметром. Эта константа используется только JDBC-стартером
     * для построения query-string.
     */
    public static final String LOAD_BALANCE_HOSTS_KEY = "loadBalanceHosts";

    /**
     * Формирует {@link ReadPoolUrlSpec} для read-пула на основе read-стратегии микросервиса.
     */
    public static ReadPoolUrlSpec resolve(PostgresClusterDataSourceProperties props) {
        return switch (props.getReadReplica().getStrategy()) {
            case LOAD_BALANCED  -> resolveLoadBalanced();
            case SECONDARY_ONLY -> resolveSecondaryOnly();
        };
    }

    private static ReadPoolUrlSpec resolveSecondaryOnly() {
        return new ReadPoolUrlSpec(SECONDARY_ONLY_TARGET_SERVER_TYPE, false, java.util.Map.of());
    }

    private static ReadPoolUrlSpec resolveLoadBalanced() {
        // loadBalanced=true — явный флаг для обоих транспортов.
        // extraParams остаётся пустым: ключ loadBalanceHosts JDBC-стартер сам кладёт
        // в URL по spec.loadBalanced(); R2DBC-стартер передаёт во builder.
        return new ReadPoolUrlSpec(LOAD_BALANCED_TARGET_SERVER_TYPE, true, java.util.Map.of());
    }
}
