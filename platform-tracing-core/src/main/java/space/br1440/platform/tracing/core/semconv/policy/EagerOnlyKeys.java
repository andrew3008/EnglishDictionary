package space.br1440.platform.tracing.core.semconv.policy;

import io.opentelemetry.api.common.AttributeKey;

import space.br1440.platform.tracing.api.semconv.SemconvKeys;

import java.util.Set;

/**
 * Реестр ключей, которые ОБЯЗАНЫ вычисляться eager (немедленно), и для которых запрещён
 * lazy {@code attribute(key, Supplier)} builder'а.
 * <p>
 * Причины запрета lazy для таких ключей:
 * <ul>
 *   <li><b>sampling-relevant</b> ({@code platform.trace.type/priority/sampling.reason}) — sampler
 *       видит только атрибуты, известные на момент создания span'а (до {@code startSpan()});</li>
 *   <li><b>используются в имени span'а</b> ({@code PlatformSpanNameBuilder}) — имя строится ДО
 *       {@code startSpan()}, а lazy-значение вычисляется уже после старта и в имя не попадёт.</li>
 * </ul>
 * Lazy-setter для такого ключа -> {@link IllegalArgumentException} (а в STRICT — также semconv
 * violation).
 */
public final class EagerOnlyKeys {

    private static final Set<AttributeKey<?>> KEYS = Set.of(
            // sampling-relevant
            SemconvKeys.PLATFORM_TYPE,
            SemconvKeys.PLATFORM_TRACE_PRIORITY,
            SemconvKeys.PLATFORM_SAMPLING_REASON,
            // используются PlatformSpanNameBuilder при построении имени
            SemconvKeys.HTTP_REQUEST_METHOD,
            SemconvKeys.HTTP_ROUTE,
            SemconvKeys.DB_OPERATION_NAME,
            SemconvKeys.DB_COLLECTION_NAME,
            SemconvKeys.RPC_SERVICE,
            SemconvKeys.RPC_METHOD,
            SemconvKeys.MESSAGING_DESTINATION_NAME,
            SemconvKeys.MESSAGING_OPERATION);

    private EagerOnlyKeys() {
        // utility-класс
    }

    /** {@code true}, если ключ обязан быть eager (lazy-setter для него запрещён). */
    public static boolean contains(AttributeKey<?> key) {
        return KEYS.contains(key);
    }
}
