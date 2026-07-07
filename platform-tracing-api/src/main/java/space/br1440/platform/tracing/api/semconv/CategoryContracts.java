package space.br1440.platform.tracing.api.semconv;

import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.api.span.SpanCategory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Реестр {@link CategoryContract} по {@link SpanCategory} — ЕДИНСТВЕННЫЙ источник истины для
 * runtime-policy ({@code AttributePolicy}) и статического линтера ({@code PlatformSpec}).
 * <p>
 * Контракты собираются один раз при загрузке класса и неизменяемы.
 */
@UtilityClass
public final class CategoryContracts {

    private static final Map<SpanCategory, CategoryContract> REGISTRY = build();

    public static CategoryContract of(SpanCategory category) {
        CategoryContract contract = REGISTRY.get(category);
        if (contract == null) {
            throw new IllegalStateException("Нет контракта для категории: " + category);
        }

        return contract;
    }

    private static Map<SpanCategory, CategoryContract> build() {
        Map<SpanCategory, CategoryContract> m = new EnumMap<>(SpanCategory.class);

        m.put(SpanCategory.INTERNAL, new CategoryContract(
                SpanCategory.INTERNAL,
                Set.of(SemconvKeys.PLATFORM_TYPE, SemconvKeys.PLATFORM_RESULT,
                        SemconvKeys.PLATFORM_REQUEST_ID, SemconvKeys.PLATFORM_TRACE_PRIORITY,
                        SemconvKeys.PLATFORM_SAMPLING_REASON),
                Set.of(SemconvKeys.PLATFORM_TYPE),
                List.of(),
                Set.of(SemconvKeys.HTTP_REQUEST_METHOD, SemconvKeys.DB_SYSTEM_NAME,
                        SemconvKeys.RPC_SYSTEM, SemconvKeys.MESSAGING_SYSTEM)));

        m.put(SpanCategory.HTTP_SERVER, new CategoryContract(
                SpanCategory.HTTP_SERVER,
                Set.of(SemconvKeys.PLATFORM_TYPE, SemconvKeys.PLATFORM_RESULT,
                        SemconvKeys.HTTP_REQUEST_METHOD, SemconvKeys.HTTP_ROUTE,
                        SemconvKeys.HTTP_RESPONSE_STATUS_CODE, SemconvKeys.URL_SCHEME,
                        SemconvKeys.SERVER_ADDRESS, SemconvKeys.SERVER_PORT,
                        SemconvKeys.NETWORK_PROTOCOL_VERSION, SemconvKeys.ERROR_TYPE),
                Set.of(SemconvKeys.PLATFORM_TYPE, SemconvKeys.HTTP_REQUEST_METHOD),
                List.of(),
                Set.of(SemconvKeys.URL_FULL)));

        m.put(SpanCategory.HTTP_CLIENT, new CategoryContract(
                SpanCategory.HTTP_CLIENT,
                Set.of(SemconvKeys.PLATFORM_TYPE, SemconvKeys.PLATFORM_RESULT,
                        SemconvKeys.PLATFORM_REMOTE_SERVICE,
                        SemconvKeys.HTTP_REQUEST_METHOD, SemconvKeys.HTTP_RESPONSE_STATUS_CODE,
                        SemconvKeys.URL_FULL, SemconvKeys.URL_SCHEME,
                        SemconvKeys.SERVER_ADDRESS, SemconvKeys.SERVER_PORT,
                        SemconvKeys.NETWORK_PROTOCOL_VERSION, SemconvKeys.ERROR_TYPE),
                Set.of(SemconvKeys.PLATFORM_TYPE, SemconvKeys.HTTP_REQUEST_METHOD),
                List.of(),
                Set.of()));

        m.put(SpanCategory.DATABASE, new CategoryContract(
                SpanCategory.DATABASE,
                Set.of(SemconvKeys.PLATFORM_TYPE, SemconvKeys.PLATFORM_RESULT,
                        SemconvKeys.PLATFORM_REMOTE_SERVICE,
                        SemconvKeys.DB_SYSTEM_NAME, SemconvKeys.DB_SYSTEM_LEGACY,
                        SemconvKeys.DB_COLLECTION_NAME, SemconvKeys.DB_NAMESPACE,
                        SemconvKeys.DB_OPERATION_NAME, SemconvKeys.ERROR_TYPE),
                Set.of(SemconvKeys.PLATFORM_TYPE),
                List.of(Set.of(SemconvKeys.DB_SYSTEM_NAME, SemconvKeys.DB_SYSTEM_LEGACY)),
                Set.of(SemconvKeys.HTTP_REQUEST_METHOD)));

        m.put(SpanCategory.RPC_SERVER, new CategoryContract(
                SpanCategory.RPC_SERVER,
                Set.of(SemconvKeys.PLATFORM_TYPE, SemconvKeys.PLATFORM_RESULT,
                        SemconvKeys.RPC_SYSTEM, SemconvKeys.RPC_SERVICE, SemconvKeys.RPC_METHOD,
                        SemconvKeys.SERVER_ADDRESS, SemconvKeys.SERVER_PORT, SemconvKeys.ERROR_TYPE),
                Set.of(SemconvKeys.PLATFORM_TYPE, SemconvKeys.RPC_SYSTEM, SemconvKeys.RPC_METHOD),
                List.of(),
                Set.of()));

        m.put(SpanCategory.RPC_CLIENT, new CategoryContract(
                SpanCategory.RPC_CLIENT,
                Set.of(SemconvKeys.PLATFORM_TYPE, SemconvKeys.PLATFORM_RESULT,
                        SemconvKeys.PLATFORM_REMOTE_SERVICE,
                        SemconvKeys.RPC_SYSTEM, SemconvKeys.RPC_SERVICE, SemconvKeys.RPC_METHOD,
                        SemconvKeys.SERVER_ADDRESS, SemconvKeys.SERVER_PORT, SemconvKeys.ERROR_TYPE),
                Set.of(SemconvKeys.PLATFORM_TYPE, SemconvKeys.RPC_SYSTEM, SemconvKeys.RPC_METHOD),
                List.of(),
                Set.of()));

        m.put(SpanCategory.KAFKA_PRODUCER, new CategoryContract(
                SpanCategory.KAFKA_PRODUCER,
                Set.of(SemconvKeys.PLATFORM_TYPE, SemconvKeys.PLATFORM_RESULT,
                        SemconvKeys.MESSAGING_SYSTEM, SemconvKeys.MESSAGING_DESTINATION_NAME,
                        SemconvKeys.MESSAGING_OPERATION, SemconvKeys.ERROR_TYPE),
                Set.of(SemconvKeys.PLATFORM_TYPE, SemconvKeys.MESSAGING_SYSTEM,
                        SemconvKeys.MESSAGING_DESTINATION_NAME),
                List.of(),
                Set.of()));

        m.put(SpanCategory.KAFKA_CONSUMER, new CategoryContract(
                SpanCategory.KAFKA_CONSUMER,
                Set.of(SemconvKeys.PLATFORM_TYPE, SemconvKeys.PLATFORM_RESULT,
                        SemconvKeys.MESSAGING_SYSTEM, SemconvKeys.MESSAGING_DESTINATION_NAME,
                        SemconvKeys.MESSAGING_OPERATION, SemconvKeys.ERROR_TYPE),
                Set.of(SemconvKeys.PLATFORM_TYPE, SemconvKeys.MESSAGING_SYSTEM,
                        SemconvKeys.MESSAGING_DESTINATION_NAME),
                List.of(),
                Set.of()));

        return new EnumMap<>(m);
    }
}
