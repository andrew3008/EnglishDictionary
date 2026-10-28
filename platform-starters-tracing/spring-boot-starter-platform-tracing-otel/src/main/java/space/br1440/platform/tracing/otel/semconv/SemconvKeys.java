package space.br1440.platform.tracing.otel.semconv;

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import io.opentelemetry.api.common.AttributeKey;
import lombok.experimental.UtilityClass;

/**
 * Внутренний реестр типизированных ключей OpenTelemetry.
 * <p>
 * Публичным источником имён атрибутов остаётся
 * {@link space.br1440.platform.tracing.api.attributes.PlatformAttributes}.
 * Типизированные ключи нужны только OTel-backed реализациям, semantic policy и внутренним тестам otel.
 */
@UtilityClass
public final class SemconvKeys {

    // platform.*
    public static final AttributeKey<String> PLATFORM_TYPE = stringKey(PlatformAttributes.PLATFORM_TYPE);
    public static final AttributeKey<String> PLATFORM_RESULT = stringKey(PlatformAttributes.PLATFORM_RESULT);
    public static final AttributeKey<String> PLATFORM_TRACE_PRIORITY = stringKey(PlatformAttributes.PLATFORM_TRACE_PRIORITY);
    public static final AttributeKey<String> PLATFORM_SAMPLING_REASON = stringKey(PlatformAttributes.PLATFORM_SAMPLING_REASON);
    public static final AttributeKey<String> PLATFORM_REQUEST_ID = stringKey(PlatformAttributes.PLATFORM_REQUEST_ID);
    public static final AttributeKey<String> PLATFORM_REMOTE_SERVICE = stringKey(PlatformAttributes.PLATFORM_REMOTE_SERVICE);

    // HTTP
    public static final AttributeKey<String> HTTP_REQUEST_METHOD = stringKey(PlatformAttributes.HTTP_REQUEST_METHOD);
    public static final AttributeKey<String> HTTP_ROUTE = stringKey(PlatformAttributes.HTTP_ROUTE);
    public static final AttributeKey<Long> HTTP_RESPONSE_STATUS_CODE = longKey(PlatformAttributes.HTTP_RESPONSE_STATUS_CODE);
    public static final AttributeKey<String> URL_FULL = stringKey(PlatformAttributes.URL_FULL);
    public static final AttributeKey<String> URL_SCHEME = stringKey(PlatformAttributes.URL_SCHEME);
    public static final AttributeKey<String> SERVER_ADDRESS = stringKey(PlatformAttributes.SERVER_ADDRESS);
    public static final AttributeKey<Long> SERVER_PORT = longKey(PlatformAttributes.SERVER_PORT);
    public static final AttributeKey<String> NETWORK_PROTOCOL_VERSION = stringKey(PlatformAttributes.NETWORK_PROTOCOL_VERSION);

    // Database
    public static final AttributeKey<String> DB_SYSTEM_NAME = stringKey(PlatformAttributes.DB_SYSTEM_NAME);
    public static final AttributeKey<String> DB_SYSTEM_LEGACY = stringKey("db.system");
    public static final AttributeKey<String> DB_COLLECTION_NAME = stringKey(PlatformAttributes.DB_COLLECTION_NAME);
    public static final AttributeKey<String> DB_NAMESPACE = stringKey(PlatformAttributes.DB_NAMESPACE);
    public static final AttributeKey<String> DB_OPERATION_NAME = stringKey(PlatformAttributes.DB_OPERATION_NAME);
    public static final AttributeKey<String> DB_RESPONSE_STATUS_CODE = stringKey(PlatformAttributes.DB_RESPONSE_STATUS_CODE);

    // RPC
    public static final AttributeKey<String> RPC_SYSTEM = stringKey(PlatformAttributes.RPC_SYSTEM_NAME);
    public static final AttributeKey<String> RPC_SERVICE = stringKey(PlatformAttributes.RPC_SERVICE);
    public static final AttributeKey<String> RPC_METHOD = stringKey(PlatformAttributes.RPC_METHOD);

    // Messaging / Kafka
    public static final AttributeKey<String> MESSAGING_SYSTEM = stringKey(PlatformAttributes.MESSAGING_SYSTEM);
    public static final AttributeKey<String> MESSAGING_DESTINATION_NAME = stringKey(PlatformAttributes.MESSAGING_DESTINATION_NAME);
    public static final AttributeKey<String> MESSAGING_OPERATION = stringKey(PlatformAttributes.MESSAGING_OPERATION);

    // Error / Exception
    public static final AttributeKey<String> ERROR_TYPE = stringKey(PlatformAttributes.ERROR_TYPE);
    public static final AttributeKey<String> EXCEPTION_TYPE = stringKey(PlatformAttributes.EXCEPTION_TYPE);
    public static final AttributeKey<String> EXCEPTION_MESSAGE = stringKey(PlatformAttributes.EXCEPTION_MESSAGE);
    public static final AttributeKey<String> EXCEPTION_STACKTRACE = stringKey(PlatformAttributes.EXCEPTION_STACKTRACE);

}
