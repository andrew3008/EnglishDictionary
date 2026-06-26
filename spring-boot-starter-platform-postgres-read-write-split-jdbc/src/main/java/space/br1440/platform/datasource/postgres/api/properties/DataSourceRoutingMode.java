package space.br1440.platform.datasource.postgres.api.properties;

/**
 * Где принимается решение о write/read-маршрутизации.
 *
 * <p>Канонический gate активации стартера: стартер активен только при
 * {@value PgClusterProperties#PREFIX}.datasource-routing-mode = CLIENT.
 */
public enum DataSourceRoutingMode {

    /**
     * Client-side read/write routing этим стартером (активирует стартер).
     */
    CLIENT,

    /**
     * Routing выполняется внешним middleware; стартер не активируется.
     */
    PROXY

}
