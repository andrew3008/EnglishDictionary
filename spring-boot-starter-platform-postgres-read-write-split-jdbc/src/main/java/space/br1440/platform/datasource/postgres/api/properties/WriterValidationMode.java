package space.br1440.platform.datasource.postgres.api.properties;

/**
 * Режим валидации соединений write-пула.
 */
public enum WriterValidationMode {

    /** connectionTestQuery не задаётся — валидация через JDBC4 Connection.isValid. */
    JDBC4,

    /** connectionTestQuery=SELECT 1; standby-guard НЕ применяется. */
    SIMPLE_QUERY,

    /** connectionInitSql отвергает standby-ноду + connectionTestQuery=SELECT 1. */
    PATRONI_PRIMARY_CHECK

}
