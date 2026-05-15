package space.br1440.platform.errorhandling.core.resolver;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.extension.ResolvedRootCause;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.core.util.CauseChainNavigator;

import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Обходит цепочку причин исключения и применяет зарегистрированные
 * {@link RootCauseExceptionHandlingStrategy} к каждому звену.
 *
 * <h2>Методы</h2>
 * <ul>
 *   <li>{@link #resolve(Throwable)} — останавливается на <b>первом</b> распознанном звене
 *       (обратная совместимость).</li>
 *   <li>{@link #resolveAll(Throwable)} — обходит <b>всю цепочку</b> и возвращает
 *       список всех распознанных звеньев в порядке от ближайшего к корневому.
 *       Используется {@code BusinessExceptionStrategy} для сборки полной цепочки
 *       {@code ERROR_INFO} в {@code ErrorEntry.details}.</li>
 * </ul>
 *
 * <p>Каждая стратегия возвращает детали по dual-metadata контракту:
 * {@code clientMetadata} для клиентского DTO и {@code diagnosticMetadata} для внутренних логов/трейсинга.
 *
 * <h2>Type-indexed dispatch</h2>
 * <p>Для снижения стоимости {@code O(N)} линейного поиска стратегии на каждое звено цепочки
 * используется {@link ConcurrentHashMap} кэш вида {@code Throwable.class → Strategy}.
 * После первого успешного матча для данного конкретного класса исключения стратегия
 * кэшируется, и все последующие запросы с тем же классом получают ответ за {@code O(1)}.
 *
 * <p><b>Контракт безопасности кэша:</b> кэш корректен тогда и только тогда, когда
 * {@link RootCauseExceptionHandlingStrategy#supportsCause(Throwable)} является
 * <em>pure-функцией по типу аргумента</em> — то есть для любых двух экземпляров
 * {@code a} и {@code b} одного и того же конкретного класса результат
 * {@code supportsCause(a) == supportsCause(b)}.
 * Если ваша стратегия проверяет не только тип (например, содержимое сообщения или
 * вложенных полей), она <b>не должна</b> использоваться совместно с кэшированием.
 *
 * <p>Ёмкость кэша ограничена {@link #MAX_CACHED_CAUSE_TYPES}. При достижении лимита
 * кэш перестаёт пополняться (не инвалидируется), а новые типы обрабатываются через
 * линейный поиск. Предупреждение логируется однократно.
 */
@Slf4j
public final class CauseChainResolver {

    static final int MAX_CACHED_CAUSE_TYPES = 256;
    static final int MAX_CAUSE_DEPTH = CauseChainNavigator.DEFAULT_MAX_DEPTH;

    private final List<RootCauseExceptionHandlingStrategy> strategies;
    private final AtomicBoolean cacheLimitLogged = new AtomicBoolean(false);
    private final ConcurrentHashMap<Class<?>, RootCauseExceptionHandlingStrategy> causeTypeCache = new ConcurrentHashMap<>();

    public CauseChainResolver(@NonNull List<RootCauseExceptionHandlingStrategy> strategies) {
        this.strategies = strategies.stream()
                .sorted(AnnotationAwareOrderComparator.INSTANCE)
                .toList();
    }

    @NonNull
    public ResolvedRootCause resolve(@NonNull Throwable topCause) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = topCause;
        int depth = 0;

        while (current != null && depth < MAX_CAUSE_DEPTH && visited.add(current)) {
            RootCauseExceptionHandlingStrategy strategy = findStrategy(current);
            if (strategy != null) {
                return new ResolvedRootCause(current, strategy.extractCauseDetails(current));
            }

            current = current.getCause();
            depth++;
        }

        return new ResolvedRootCause(topCause, buildFallbackDetails(topCause));
    }

    /**
     * Обходит <b>всю</b> цепочку причин и возвращает список всех распознанных звеньев.
     *
     * <p>Алгоритм:
     * <ol>
     *   <li>Начинает с {@code topCause}.</li>
     *   <li>Если текущее звено распознаётся какой-либо стратегией — добавляет
     *       {@link ResolvedRootCause} в результат и переходит к {@code cause.getCause()}.</li>
     *   <li>Если текущее звено не распознаётся — переходит к следующему без добавления.</li>
     *   <li>Останавливается при {@code cause == null}, зацикливании (IdentityHashMap)
     *       или превышении {@link #MAX_CAUSE_DEPTH}.</li>
     *   <li>Если ни одно звено не распознано — возвращает fallback с единственным элементом.</li>
     * </ol>
     *
     * <p>Порядок в списке: ближайший resolved cause первый, корневой — последний.
     */
    @NonNull
    public List<ResolvedRootCause> resolveAll(@NonNull Throwable topCause) {
        List<ResolvedRootCause> chain = collectMatchedChain(topCause);

        if (chain.isEmpty()) {
            chain.add(new ResolvedRootCause(topCause, buildFallbackDetails(topCause)));
        }

        return Collections.unmodifiableList(chain);
    }

    /**
     * Возвращает только звенья цепочки, фактически распознанные зарегистрированными стратегиями.
     *
     * <p>Отличие от {@link #resolveAll(Throwable)}: при отсутствии матчей возвращается
     * <b>пустой</b> список, без synthetic fallback-резолюции с {@code httpStatus=500}
     * и {@code reason=FQCN}.
     *
     * <p>Применяется композитными стратегиями (например, {@code RetryExhaustionExceptionStrategy}),
     * которым нужно различать «есть точная классификация underlying cause» и «нет —
     * используем собственные дефолты», а не получать вырожденный fallback,
     * подмешивающий мусорные http-статус и reason в финальный результат.
     */
    @NonNull
    public List<ResolvedRootCause> resolveAllStrict(@NonNull Throwable topCause) {
        return Collections.unmodifiableList(collectMatchedChain(topCause));
    }

    @NonNull
    private List<ResolvedRootCause> collectMatchedChain(@NonNull Throwable topCause) {
        List<ResolvedRootCause> chain = new ArrayList<>();

        int depth = 0;
        Throwable current = topCause;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        while (current != null && depth < MAX_CAUSE_DEPTH && visited.add(current)) {
            RootCauseExceptionHandlingStrategy strategy = findStrategy(current);
            if (strategy != null) {
                chain.add(new ResolvedRootCause(current, strategy.extractCauseDetails(current)));
            }

            current = current.getCause();
            depth++;
        }

        return chain;
    }

    @Nullable
    private RootCauseExceptionHandlingStrategy findStrategy(@NonNull Throwable cause) {
        RootCauseExceptionHandlingStrategy cached = causeTypeCache.get(cause.getClass());
        if (cached != null) {
            return cached;
        }

        for (RootCauseExceptionHandlingStrategy strategy : strategies) {
            if (strategy.supportsCause(cause)) {
                cacheIfEligible(cause.getClass(), strategy);
                return strategy;
            }
        }

        return null;
    }

    private void cacheIfEligible(@NonNull Class<?> causeType,
                                 @NonNull RootCauseExceptionHandlingStrategy strategy) {
        if (causeTypeCache.size() >= MAX_CACHED_CAUSE_TYPES) {
            if (cacheLimitLogged.compareAndSet(false, true)) {
                String message = """
                    CauseChain strategy type cache reached capacity ({}) — \
                    new cause types will fall back to linear scan. \
                    This typically indicates a very large number of distinct exception types.\
                    """;
                log.warn(message, MAX_CACHED_CAUSE_TYPES);
            }

            return;
        }

        causeTypeCache.putIfAbsent(causeType, strategy);
    }

    @NonNull
    private static RootCauseResolution buildFallbackDetails(@NonNull Throwable cause) {
        Map<String, String> diagnosticMeta = new LinkedHashMap<>();
        diagnosticMeta.put("rootCauseClass", cause.getClass().getName());

        String rawMessage = cause.getMessage();
        if (rawMessage != null && !rawMessage.isBlank()) {
            diagnosticMeta.put("rootCauseMessage", rawMessage);
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(cause.getClass().getName())
                .diagnosticMetadata(diagnosticMeta)
                .build();
        return RootCauseResolution.of(HttpStatus.INTERNAL_SERVER_ERROR, "An internal error occurred", List.of(info));
    }
}
