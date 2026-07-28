package space.br1440.platform.tracing.otel.javaagent.control.state;

/**
 * Закрытый набор уровней диагностических сообщений Agent.
 */
enum PolicyLogLevel {
    OFF,
    ERROR,
    WARN,
    INFO,
    DEBUG,
    TRACE
}
