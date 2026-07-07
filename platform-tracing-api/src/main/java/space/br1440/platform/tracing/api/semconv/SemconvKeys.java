package space.br1440.platform.tracing.api.semconv;

import io.opentelemetry.api.common.AttributeKey;
import lombok.experimental.UtilityClass;

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Реестр типизированных ключей атрибутов ({@link AttributeKey}) — compatibility layer поверх
 * строковых констант {@link space.br1440.platform.tracing.api.attributes.PlatformAttributes}.
 * <p>
 * Ключи в группах HTTP, Database, RPC, Messaging и Error/Exception следуют
 * официальным OpenTelemetry Semantic Conventions и Attribute Registry:
 * <ul>
 *   <li>Semantic Conventions overview:
 *       <a href="https://opentelemetry.io/docs/specs/semconv/">...</a></li>
 *   <li>Attribute Registry (единый реестр атрибутов):
 *       <a href="https://opentelemetry.io/docs/specs/semconv/registry/attributes/">...</a></li>
 *   <li>HTTP attributes (http.request.method, http.route, http.response.status_code,
 *       url.full, server.address, server.port, network.protocol.version):
 *       <a href="https://opentelemetry.io/docs/specs/semconv/registry/attributes/http/">...</a></li>
 *   <li>Database conventions (db.system.name, db.system, db.collection.name, db.namespace, db.operation.name):
 *       <a href="https://opentelemetry.io/docs/specs/semconv/db/">...</a></li>
 *   <li>RPC attributes (rpc.system, rpc.service, rpc.method):
 *       <a href="https://github.com/open-telemetry/semantic-conventions/blob/main/docs/registry/attributes/rpc.md">...</a></li>
 *   <li>Messaging attributes (messaging.system, messaging.destination.name, messaging.operation):
 *       <a href="https://opentelemetry.io/docs/specs/semconv/registry/attributes/messaging/">...</a></li>
 *   <li>Reserved error/exception attributes (error.type, exception.type, exception.message, exception.stacktrace):
 *       <a href="https://opentelemetry.io/docs/specs/otel/semantic-conventions/">...</a></li>
 * </ul>
 */
@UtilityClass
public final class SemconvKeys {

    // -- platform.* (стабильные платформенные ключи) -------------------------------------------

    /** {@code platform.trace.type} — категория span'а ({@link space.br1440.platform.tracing.api.span.SpanCategory}). */
    public static final AttributeKey<String> PLATFORM_TYPE = stringKey("platform.trace.type");

    /** {@code platform.trace.result} — финальный статус операции. */
    public static final AttributeKey<String> PLATFORM_RESULT = stringKey("platform.trace.result");

    /** {@code platform.trace.priority} — приоритет трассы (sampling-relevant). */
    public static final AttributeKey<String> PLATFORM_TRACE_PRIORITY = stringKey("platform.trace.priority");

    /** {@code platform.sampling.reason} — причина head-sampling решения (sampling-relevant). */
    public static final AttributeKey<String> PLATFORM_SAMPLING_REASON = stringKey("platform.sampling.reason");

    /** {@code platform.request_id} — correlation id (high-cardinality, НЕ для метрик). */
    public static final AttributeKey<String> PLATFORM_REQUEST_ID = stringKey("platform.request_id");

    /** {@code platform.user_hash} — псевдонимизированный идентификатор пользователя (НЕ raw id/PII). */
    public static final AttributeKey<String> PLATFORM_USER_HASH = stringKey("platform.user_hash");

    /** {@code platform.remote.service} — логическое имя upstream-сервиса. */
    public static final AttributeKey<String> PLATFORM_REMOTE_SERVICE = stringKey("platform.remote.service");

    // -- HTTP (stable semconv) -----------------------------------------------------------------
    public static final AttributeKey<String> HTTP_REQUEST_METHOD = stringKey("http.request.method");
    public static final AttributeKey<String> HTTP_ROUTE = stringKey("http.route");
    public static final AttributeKey<Long> HTTP_RESPONSE_STATUS_CODE = longKey("http.response.status_code");
    public static final AttributeKey<String> URL_FULL = stringKey("url.full");
    public static final AttributeKey<String> URL_SCHEME = stringKey("url.scheme");
    public static final AttributeKey<String> SERVER_ADDRESS = stringKey("server.address");
    public static final AttributeKey<Long> SERVER_PORT = longKey("server.port");
    public static final AttributeKey<String> NETWORK_PROTOCOL_VERSION = stringKey("network.protocol.version");

    // -- Database (stable + legacy, см. ADR-db-semconv-detection) ------------------------------
    /** {@code db.system.name} — стабильный (semconv 1.28+). */
    public static final AttributeKey<String> DB_SYSTEM_NAME = stringKey("db.system.name");
    /** {@code db.system} — legacy (≤1.27), поддерживается для совместимости с Agent 2.28.x. */
    public static final AttributeKey<String> DB_SYSTEM_LEGACY = stringKey("db.system");
    public static final AttributeKey<String> DB_COLLECTION_NAME = stringKey("db.collection.name");
    public static final AttributeKey<String> DB_NAMESPACE = stringKey("db.namespace");
    public static final AttributeKey<String> DB_OPERATION_NAME = stringKey("db.operation.name");

    // -- RPC (stable semconv) ------------------------------------------------------------------
    public static final AttributeKey<String> RPC_SYSTEM = stringKey("rpc.system");
    public static final AttributeKey<String> RPC_SERVICE = stringKey("rpc.service");
    public static final AttributeKey<String> RPC_METHOD = stringKey("rpc.method");

    // -- Messaging / Kafka (stable semconv) ----------------------------------------------------
    public static final AttributeKey<String> MESSAGING_SYSTEM = stringKey("messaging.system");
    public static final AttributeKey<String> MESSAGING_DESTINATION_NAME = stringKey("messaging.destination.name");
    public static final AttributeKey<String> MESSAGING_OPERATION = stringKey("messaging.operation");

    // -- Error / Exception (stable semconv) ----------------------------------------------------
    public static final AttributeKey<String> ERROR_TYPE = stringKey("error.type");
    public static final AttributeKey<String> EXCEPTION_TYPE = stringKey("exception.type");
    public static final AttributeKey<String> EXCEPTION_MESSAGE = stringKey("exception.message");
    public static final AttributeKey<String> EXCEPTION_STACKTRACE = stringKey("exception.stacktrace");

}
