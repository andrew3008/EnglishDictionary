package space.br1440.platform.tracing.otel.naming;

import io.opentelemetry.api.common.Attributes;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanCategory;

/**
 * Строит low-cardinality имя span'а по категории и нормализованным атрибутам
 * (форма из {@code opentelemetry-java-instrumentation} SpanNameExtractor'ов):
 * <ul>
 *   <li>HTTP — {@code {method} {route}} (route template, не raw URL);</li>
 *   <li>DB — {@code {operation} {collection}} (НИКОГДА не raw SQL с литералами);</li>
 *   <li>RPC — {@code {service}/{method}};</li>
 *   <li>Kafka — {@code {destination} {operation}};</li>
 *   <li>Internal — явное имя.</li>
 * </ul>
 * Имена строятся до {@code startSpan()} из единого snapshot нормализованных атрибутов.
 * Если необходимых атрибутов нет — используется явно заданное имя, иначе низко-кардинальный fallback по категории.
 */
@UtilityClass
public final class PlatformSpanNameBuilder {

    @Nonnull
    public static String forCategory(@Nonnull SpanCategory category,
                                     @Nonnull Attributes attributes,
                                     @Nullable String explicitName) {
        String name = switch (category) {
            case HTTP_SERVER, HTTP_CLIENT -> http(attributes, explicitName);
            case DATABASE -> database(attributes, explicitName);
            case RPC_SERVER, RPC_CLIENT -> rpc(attributes, explicitName);
            case KAFKA_PRODUCER, KAFKA_CONSUMER -> kafka(attributes, explicitName);
            case INTERNAL -> orFallback(explicitName, "internal");
        };
        return name == null || name.isBlank() ? category.value() : name;
    }

    private static String http(Attributes attrs, String explicitName) {
        String method = attrs.get(SemconvKeys.HTTP_REQUEST_METHOD);
        String route = attrs.get(SemconvKeys.HTTP_ROUTE);
        if ((method != null) && (route != null)) {
            return method + ' ' + route;
        }

        if (method != null) {
            return method;
        }

        return explicitName;
    }

    private static String database(Attributes attrs, String explicitName) {
        String operation = attrs.get(SemconvKeys.DB_OPERATION_NAME);
        String collection = attrs.get(SemconvKeys.DB_COLLECTION_NAME);
        if ((operation != null) && (collection != null)) {
            return operation + ' ' + collection;
        }

        if (operation != null) {
            return operation;
        }

        return explicitName;
    }

    private static String rpc(Attributes attrs, String explicitName) {
        String service = attrs.get(SemconvKeys.RPC_SERVICE);
        String method = attrs.get(SemconvKeys.RPC_METHOD);
        if ((service != null) && (method != null)) {
            return service + '/' + method;
        }

        if (method != null) {
            return method;
        }

        return explicitName;
    }

    private static String kafka(Attributes attrs, String explicitName) {
        String destination = attrs.get(SemconvKeys.MESSAGING_DESTINATION_NAME);
        String operation = attrs.get(SemconvKeys.MESSAGING_OPERATION);
        if ((destination != null) && (operation != null)) {
            return destination + ' ' + operation;
        }

        if (destination != null) {
            return destination;
        }

        return explicitName;
    }

    @SuppressWarnings("SameParameterValue")
    private static String orFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
