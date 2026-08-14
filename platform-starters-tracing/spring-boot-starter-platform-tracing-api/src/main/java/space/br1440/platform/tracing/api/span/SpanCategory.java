package space.br1440.platform.tracing.api.span;

/**
 * Перечень поддерживаемых категорий span'а.
 */
public enum SpanCategory {

    /** Входящий HTTP-запрос. */
    HTTP_SERVER("http_server"),

    /** Исходящий HTTP-запрос. */
    HTTP_CLIENT("http_client"),

    /** Операция с базой данных (PostgreSQL, иной источник). */
    DATABASE("database"),

    /** Входящий RPC-вызов (gRPC server, иной brokered-протокол со стороны сервера). */
    RPC_SERVER("rpc_server"),

    /** Исходящий RPC-вызов (gRPC client, иной brokered-протокол со стороны клиента). */
    RPC_CLIENT("rpc_client"),

    /** Отправка сообщения в брокер (Kafka producer и аналоги). */
    KAFKA_PRODUCER("kafka_producer"),

    /** Обработка сообщения из брокера (Kafka consumer и аналоги). */
    KAFKA_CONSUMER("kafka_consumer"),

    /** Внутренний span бизнес-операции, не пересекающий сетевую границу. */
    INTERNAL("internal");

    private final String value;

    SpanCategory(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
