package space.br1440.platform.tracing.api.span;

/**
 * Категория span'а в терминах платформенного стандарта.
 * <p>
 * Значение публикуется в атрибут {@code platform.type} и используется при построении типизированных
 * span'ов и при принятии решений о сэмплировании.
 */
public enum SpanCategory {

    /** Входящий HTTP-запрос. */
    HTTP_SERVER("http_server"),

    /** Исходящий HTTP-запрос. */
    HTTP_CLIENT("http_client"),

    /** Операция с базой данных (PostgreSQL, иной источник). */
    DATABASE("database"),

    /** RPC-операция (gRPC, Kafka, иной brokered-протокол). */
    RPC("rpc"),

    /** Внутренний span бизнес-операции, не пересекающий сетевую границу. */
    INTERNAL("internal"),

    /** Технический span инструментации платформы (служебные операции стартера). */
    PLATFORM("platform");

    private final String value;

    SpanCategory(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
