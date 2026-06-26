package space.br1440.platform.datasource.postgres.api.routing;

public enum DataSourceType {

    /** Операция записи — направляется в write-пул (bean {@code writeDataSource}). */
    WRITE,

    /** Операция чтения — направляется в read-пул (bean {@code readDataSource}). */
    READ

}
