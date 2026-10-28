package space.br1440.platform.tracing.api.attributes;

import lombok.experimental.UtilityClass;

/**
 * Канонические имена атрибутов span'ов, используемые платформенным решением.
 * <p>
 * Атрибуты с префиксом {@code platform.} относятся к платформенному namespace
 * и не пересекаются с OpenTelemetry semantic conventions. Атрибуты без префикса
 * (HTTP / DB / RPC) — строковые литералы, выровненные с актуальной спецификацией
 * OpenTelemetry semantic conventions (см. Attributes Registry ниже).
 * <p>
 * Класс намеренно <b>не</b> зависит от generated-классов
 * {@code io.opentelemetry.semconv:opentelemetry-semconv}, чтобы {@code platform-tracing-api}
 * оставался лёгким и не привязывал compile-time к конкретной версии semconv-jar.
 * Согласованность runtime обеспечивается platform BOM ({@code opentelemetry-bom},
 * {@code opentelemetry-instrumentation-bom}); при апгрейде BOM сверяйте ключи с Registry.
 * <p>
 * См. <a href="https://opentelemetry.io/docs/specs/semconv/attributes-registry/">OTel Attributes Registry</a>.
 */
@UtilityClass
public final class PlatformAttributes {

    // *********************************************************************************************************
    // Платформенные атрибуты
    // *********************************************************************************************************

    /** Категория span'а согласно платформенному стандарту. См. {@link space.br1440.platform.tracing.api.span.SpanCategory}. */
    public static final String PLATFORM_TYPE = "platform.trace.type";

    /** Финальный статус операции согласно платформенному стандарту. См. {@link space.br1440.platform.tracing.api.span.SpanResult}. */
    public static final String PLATFORM_RESULT = "platform.trace.result";

    /**
     * Среда исполнения (OTel semconv {@code deployment.environment.name}; legacy: {@code deployment.environment}).
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/attributes-registry/deployment/">OTel: deployment attributes</a>
     */
    public static final String PLATFORM_ENVIRONMENT = "deployment.environment.name";

    /** Признак принудительного завершения span'а по span timeout: {@code span} или {@code trace}. */
    public static final String PLATFORM_TIMEOUT = "platform.trace.timeout";

    /**
     * Канонический идентификатор хоста (OTel semconv {@code host.name}).
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/attributes-registry/host/">OTel: host attributes</a>
     */
    public static final String PLATFORM_HOST = "host.name";

    /** Класс длительности trace. */
    public static final String PLATFORM_TRACE_DURATION_CLASS = "platform.trace.duration_class";

    /** Приоритет trace. */
    public static final String PLATFORM_TRACE_PRIORITY = "platform.trace.priority";

    /**
     * Container runtime ID (OpenTelemetry semconv {@code container.id}).
     * <p>
     * Не путать с Pod UID из Kubernetes Downward API — это отдельный атрибут {@code k8s.pod.uid}.
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/attributes-registry/container/">OTel: container attributes</a>
     */
    public static final String CONTAINER_ID = "container.id";

    /**
     * Имя метода, помеченного {@code @Traced}, при обогащении active span'а (режим {@code ENRICH_CURRENT}).
     */
    public static final String PLATFORM_TRACED_METHOD = "platform.traced.method";

    /**
     * Логическое имя upstream-сервиса на ERROR CLIENT span'е: какой peer уронил исходящий вызов.
     * <p>
     * Это телеметрический атрибут span'а. Он не является ключом MDC и не является источником
     * {@code domain} для error DTO: конверт ошибки владеет ErrorHandling через upstream JSON,
     * затем backfill {@code spring.application.name}.
     */
    public static final String PLATFORM_REMOTE_SERVICE = "platform.remote.service";

    /** Причина head-sampling решения (см. {@code CompositeSampler}). */
    public static final String PLATFORM_SAMPLING_REASON = "platform.sampling.reason";

    /**
     * Идентификатор запроса (correlation id) из внешнего HTTP-заголовка.
     * <p>
     * <b>Внимание:</b> значение является high-cardinality. Допустимо использовать его как атрибут span'а,
     * но <b>категорически запрещено</b> передавать этот атрибут как dimension/tag в метрики Micrometer
     * или OpenTelemetry Metrics, так как это приведёт к cardinality explosion и отказу TSDB (Prometheus/ClickHouse).
     */
    public static final String PLATFORM_REQUEST_ID = "platform.request_id";

    /**
     * Бизнес-идентификатор корреляции. Значение является high-cardinality и не должно
     * использоваться как dimension/tag метрик.
     */
    public static final String PLATFORM_CORRELATION_ID = "platform.correlation_id";


    // *********************************************************************************************************
    // Атрибуты OpenTelemetry semantic conventions (string literals; без зависимости на semconv-jar / SDK)
    // *********************************************************************************************************

    /**
     * Логическое имя сервиса (OTel semconv {@code service.name}).
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/attributes-registry/service/">OTel: service attributes</a>
     */
    public static final String SERVICE_NAME = "service.name";

    /**
     * Версия сервиса (OTel semconv {@code service.version}).
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/attributes-registry/service/">OTel: service attributes</a>
     */
    public static final String SERVICE_VERSION = "service.version";

    /**
     * Метод HTTP-запроса (OTel semconv {@code http.request.method}; legacy: {@code http.method}).
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/attributes-registry/http/">OTel: http attributes</a>
     */
    public static final String HTTP_REQUEST_METHOD = "http.request.method";

    /**
     * Исходный метод HTTP-запроса до нормализации или замещения
     * (OTel semconv {@code http.request.method_original}).
     * Проставляется HTTP instrumentation (Spring MVC, WebClient и т.д.),
     * когда фактический method отличается от {@link #HTTP_REQUEST_METHOD}.
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/http/http-spans/">OTel: HTTP spans</a>
     */
    @SuppressWarnings("unused")
    public static final String HTTP_REQUEST_METHOD_ORIGINAL = "http.request.method_original";

    /** Нормализованный шаблон HTTP-маршрута (OTel semconv {@code http.route}). */
    public static final String HTTP_ROUTE = "http.route";

    /**
     * HTTP response status code, integer (OTel semconv {@code http.response.status_code};
     * legacy: {@code http.status_code}, тип string → integer).
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/attributes-registry/http/">OTel: http attributes</a>
     */
    public static final String HTTP_RESPONSE_STATUS_CODE = "http.response.status_code";

    /**
     * Absolute URL, описывающий network resource (OTel semconv {@code url.full}; legacy: {@code http.url}).
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/attributes-registry/url/">OTel: url attributes</a>
     */
    public static final String URL_FULL = "url.full";

    /** Схема URL (OTel semconv {@code url.scheme}). */
    public static final String URL_SCHEME = "url.scheme";

    /**
     * Hostname или IP-адрес сервера (OTel semconv {@code server.address}; legacy: {@code net.peer.name}).
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/attributes-registry/server/">OTel: server attributes</a>
     */
    public static final String SERVER_ADDRESS = "server.address";

    /**
     * Номер порта сервера (OTel semconv {@code server.port}; legacy: {@code net.peer.port}).
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/attributes-registry/server/">OTel: server attributes</a>
     */
    public static final String SERVER_PORT = "server.port";

    /**
     * Имя протокола OSI application layer (OTel semconv {@code network.protocol.name}; legacy: {@code net.app.protocol.name}).
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/attributes-registry/network/">OTel: network attributes</a>
     */
    @SuppressWarnings("unused")
    public static final String NETWORK_PROTOCOL_NAME = "network.protocol.name";

    /** Версия сетевого протокола (OTel semconv {@code network.protocol.version}). */
    public static final String NETWORK_PROTOCOL_VERSION = "network.protocol.version";

    /**
     * Класс ошибки, с которой завершилась операция span'а (OTel semconv {@code error.type}).
     * <p>
     * Для database span при принятом коде ответа БД значение совпадает с {@code db.response.status_code}.
     * Имя класса исключения при этом остаётся в {@code exception.type}.
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/attributes-registry/error/">OTel: error attributes</a>
     */
    public static final String ERROR_TYPE = "error.type";

    /**
     * Имя database management system (OTel semconv {@code db.system.name}; legacy: {@code db.system}).
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/attributes-registry/db/">OTel: db attributes</a>
     */
    public static final String DB_SYSTEM_NAME = "db.system.name";

    /** Имя коллекции или таблицы БД (OTel semconv {@code db.collection.name}). */
    public static final String DB_COLLECTION_NAME = "db.collection.name";

    /** Пространство имён БД (OTel semconv {@code db.namespace}). */
    public static final String DB_NAMESPACE = "db.namespace";

    /**
     * Имя операции или команды базы данных (OTel semconv {@code db.operation.name}; legacy: {@code db.operation}).
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/attributes-registry/db/">OTel: db attributes</a>
     */
    public static final String DB_OPERATION_NAME = "db.operation.name";

    /**
     * Код ответа БД, строка (OTel semconv {@code db.response.status_code}).
     * <p>
     * Примеры реестра semconv 1.28.0: {@code 102}, {@code ORA-17002}, {@code 08P01}, {@code 404}.
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/attributes-registry/db/">OTel: db attributes</a>
     */
    @SuppressWarnings("unused")
    public static final String DB_RESPONSE_STATUS_CODE = "db.response.status_code";

    /**
     * Идентификатор RPC-системы (OTel semconv {@code rpc.system.name}; legacy: {@code rpc.system}).
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/attributes-registry/rpc/">OTel: rpc attributes</a>
     */
    public static final String RPC_SYSTEM_NAME = "rpc.system.name";

    /** Имя RPC-сервиса (OTel semconv {@code rpc.service}). */
    public static final String RPC_SERVICE = "rpc.service";

    /** Имя RPC-метода (OTel semconv {@code rpc.method}). */
    public static final String RPC_METHOD = "rpc.method";

    /**
     * Код ответа RPC, возвращённый server'ом (OTel semconv {@code rpc.response.status_code}).
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/attributes-registry/rpc/">OTel: rpc attributes</a>
     */
    @SuppressWarnings("unused")
    public static final String RPC_RESPONSE_STATUS_CODE = "rpc.response.status_code";

    /** Идентификатор messaging-системы (OTel semconv {@code messaging.system}). */
    public static final String MESSAGING_SYSTEM = "messaging.system";

    /** Имя назначения сообщения (OTel semconv {@code messaging.destination.name}). */
    public static final String MESSAGING_DESTINATION_NAME = "messaging.destination.name";

    /** Операция над сообщением (OTel semconv {@code messaging.operation}). */
    public static final String MESSAGING_OPERATION = "messaging.operation";

    /**
     * Exception message (OTel semconv {@code exception.message}).
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/attributes-registry/exception/">OTel: exception attributes</a>
     */
    public static final String EXCEPTION_MESSAGE = "exception.message";

    /**
     * Exception type, обычно class name (OTel semconv {@code exception.type}).
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/attributes-registry/exception/">OTel: exception attributes</a>
     */
    public static final String EXCEPTION_TYPE = "exception.type";

    /** Стек вызовов исключения (OTel semconv {@code exception.stacktrace}). */
    public static final String EXCEPTION_STACKTRACE = "exception.stacktrace";

}
