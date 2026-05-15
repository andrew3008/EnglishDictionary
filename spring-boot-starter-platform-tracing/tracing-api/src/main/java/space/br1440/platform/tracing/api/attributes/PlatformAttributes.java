package space.br1440.platform.tracing.api.attributes;

/**
 * Канонические имена атрибутов span'ов, используемые во всём платформенном ландшафте.
 * <p>
 * Атрибуты, начинающиеся с префикса {@code platform.}, относятся к платформенному пространству имён
 * и не пересекаются с OpenTelemetry semantic conventions. Атрибуты без префикса (HTTP / DB / RPC)
 * соответствуют OpenTelemetry semantic conventions версии, зафиксированной в платформенном BOM.
 * <p>
 * Класс является финальным utility-контейнером констант и не должен инстанцироваться.
 */
public final class PlatformAttributes {

    private PlatformAttributes() {
        // utility-класс
    }

    // -- Платформенные атрибуты ----------------------------------------------------------------

    /** Категория span'а согласно платформенному стандарту. См. {@link space.br1440.platform.tracing.api.span.SpanCategory}. */
    public static final String PLATFORM_TYPE = "platform.type";

    /** Финальный статус операции согласно платформенному стандарту. См. {@link space.br1440.platform.tracing.api.span.SpanResult}. */
    public static final String PLATFORM_RESULT = "platform.result";

    /** Идентификатор организационной группы (C-Group), которой принадлежит сервис. */
    public static final String PLATFORM_C_GROUP = "platform.c_group";

    /** Идентификатор сервиса в реестре платформы. */
    public static final String PLATFORM_ID = "platform.id";

    /** Среда исполнения: dev, stage, prod, иное согласованное значение. */
    public static final String PLATFORM_ENVIRONMENT = "platform.environment";

    /** Признак принудительного завершения span'а сторожевым таймером (см. SpanWatchdogProcessor). */
    public static final String PLATFORM_TIMEOUT = "platform.timeout";

    /** Канонический идентификатор инстанса/пода/контейнера, на котором работает сервис. */
    public static final String PLATFORM_HOST = "platform.host";

    /**
     * Логическое имя upstream-сервиса для ERROR'ных клиентских span'ов и сопутствующего MDC-ключа.
     * <p>
     * Платформенный атрибут, надстраиваемый над OpenTelemetry-семантикой (см.
     * {@code peer.service} / {@code rpc.service} / {@code server.address}). Имя стабильно и
     * безопасно для использования в качестве значения {@code domain} при формировании DTO
     * ошибки внешним errorhandling-стартером — поле single-source-of-truth, которое заполняется
     * платформой однократно и не зависит от типа клиента (HTTP / gRPC / messaging).
     */
    public static final String PLATFORM_REMOTE_SERVICE = "platform.remote.service";

    // -- Атрибуты OpenTelemetry semantic conventions (для удобства, чтобы не полагаться на классы SDK) ----

    public static final String SERVICE_NAME = "service.name";
    public static final String SERVICE_VERSION = "service.version";

    public static final String HTTP_REQUEST_METHOD = "http.request.method";
    public static final String HTTP_RESPONSE_STATUS_CODE = "http.response.status_code";
    public static final String URL_FULL = "url.full";
    public static final String SERVER_ADDRESS = "server.address";
    public static final String SERVER_PORT = "server.port";
    public static final String NETWORK_PROTOCOL_NAME = "network.protocol.name";
    public static final String ERROR_TYPE = "error.type";

    public static final String DB_SYSTEM_NAME = "db.system.name";
    public static final String DB_OPERATION_NAME = "db.operation.name";
    public static final String DB_RESPONSE_STATUS_CODE = "db.response.status_code";

    public static final String RPC_SYSTEM_NAME = "rpc.system.name";
    public static final String RPC_RESPONSE_STATUS_CODE = "rpc.response.status_code";

    public static final String EXCEPTION_MESSAGE = "exception.message";
    public static final String EXCEPTION_TYPE = "exception.type";
}
