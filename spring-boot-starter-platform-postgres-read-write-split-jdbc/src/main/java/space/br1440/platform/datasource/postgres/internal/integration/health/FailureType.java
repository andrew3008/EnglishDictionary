package space.br1440.platform.datasource.postgres.internal.integration.health;

enum FailureType {
    TIMEOUT,
    EXECUTOR_REJECTED,
    SHUTTING_DOWN,
    CONNECTION_FAILURE,
    SQL_EXCEPTION,
    INVALID_RESULT,
    INTERNAL_ERROR
}
