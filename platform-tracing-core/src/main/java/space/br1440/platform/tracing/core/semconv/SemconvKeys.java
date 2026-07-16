package space.br1440.platform.tracing.core.semconv;

import io.opentelemetry.api.common.AttributeKey;
import lombok.experimental.UtilityClass;

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Внутренний реестр типизированных ключей OpenTelemetry.
 * <p>
 * Публичным источником имён атрибутов остаётся
 * {@link space.br1440.platform.tracing.api.attributes.PlatformAttributes}. Типизированные ключи
 * нужны только OTel-backed реализациям, semantic policy и внутренним тестам core.
 */
@UtilityClass
public final class SemconvKeys {

    // -- platform.* -------------------------------------------------------------------------------

    public static final AttributeKey<String> PLATFORM_TYPE = stringKey("platform.trace.type");
    public static final AttributeKey<String> PLATFORM_RESULT = stringKey("platform.trace.result");
    public static final AttributeKey<String> PLATFORM_TRACE_PRIORITY = stringKey("platform.trace.priority");
    public static final AttributeKey<String> PLATFORM_SAMPLING_REASON = stringKey("platform.sampling.reason");
    public static final AttributeKey<String> PLATFORM_REQUEST_ID = stringKey("platform.request_id");
    public static final AttributeKey<String> PLATFORM_USER_HASH = stringKey("platform.user_hash");
    public static final AttributeKey<String> PLATFORM_REMOTE_SERVICE = stringKey("platform.remote.service");

    // -- HTTP -------------------------------------------------------------------------------------

    public static final AttributeKey<String> HTTP_REQUEST_METHOD = stringKey("http.request.method");
    public static final AttributeKey<String> HTTP_ROUTE = stringKey("http.route");
    public static final AttributeKey<Long> HTTP_RESPONSE_STATUS_CODE = longKey("http.response.status_code");
    public static final AttributeKey<String> URL_FULL = stringKey("url.full");
    public static final AttributeKey<String> URL_SCHEME = stringKey("url.scheme");
    public static final AttributeKey<String> SERVER_ADDRESS = stringKey("server.address");
    public static final AttributeKey<Long> SERVER_PORT = longKey("server.port");
    public static final AttributeKey<String> NETWORK_PROTOCOL_VERSION = stringKey("network.protocol.version");

    // -- Database ---------------------------------------------------------------------------------

    public static final AttributeKey<String> DB_SYSTEM_NAME = stringKey("db.system.name");
    public static final AttributeKey<String> DB_SYSTEM_LEGACY = stringKey("db.system");
    public static final AttributeKey<String> DB_COLLECTION_NAME = stringKey("db.collection.name");
    public static final AttributeKey<String> DB_NAMESPACE = stringKey("db.namespace");
    public static final AttributeKey<String> DB_OPERATION_NAME = stringKey("db.operation.name");

    // -- RPC --------------------------------------------------------------------------------------

    public static final AttributeKey<String> RPC_SYSTEM = stringKey("rpc.system");
    public static final AttributeKey<String> RPC_SERVICE = stringKey("rpc.service");
    public static final AttributeKey<String> RPC_METHOD = stringKey("rpc.method");

    // -- Messaging / Kafka ------------------------------------------------------------------------

    public static final AttributeKey<String> MESSAGING_SYSTEM = stringKey("messaging.system");
    public static final AttributeKey<String> MESSAGING_DESTINATION_NAME = stringKey("messaging.destination.name");
    public static final AttributeKey<String> MESSAGING_OPERATION = stringKey("messaging.operation");

    // -- Error / Exception ------------------------------------------------------------------------

    public static final AttributeKey<String> ERROR_TYPE = stringKey("error.type");
    public static final AttributeKey<String> EXCEPTION_TYPE = stringKey("exception.type");
    public static final AttributeKey<String> EXCEPTION_MESSAGE = stringKey("exception.message");
    public static final AttributeKey<String> EXCEPTION_STACKTRACE = stringKey("exception.stacktrace");
}
