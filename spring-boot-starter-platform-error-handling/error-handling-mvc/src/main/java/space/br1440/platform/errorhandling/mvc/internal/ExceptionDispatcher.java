package space.br1440.platform.errorhandling.mvc.internal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.model.ErrorEntry;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public final class ExceptionDispatcher {

    public static final String UNKNOWN_STRATEGY_NAME = "unknown";

    public static final int DEFAULT_MAX_CACHED_TYPES = 256;

    private final List<TopExceptionHandlingStrategy> strategies;
    private final UnknownExceptionHandler unknownHandler;
    private final int maxCachedTypes;

    private final ConcurrentHashMap<Class<? extends Exception>, TopExceptionHandlingStrategy> typeCache = new ConcurrentHashMap<>();

    private final AtomicBoolean capacityWarningEmitted = new AtomicBoolean(false);

    public ExceptionDispatcher(@NonNull List<TopExceptionHandlingStrategy> strategies,
                               @NonNull UnknownExceptionHandler unknownHandler) {
        this(strategies, unknownHandler, DEFAULT_MAX_CACHED_TYPES);
    }

    public ExceptionDispatcher(@NonNull List<TopExceptionHandlingStrategy> strategies,
                               @NonNull UnknownExceptionHandler unknownHandler,
                               int maxCachedTypes) {
        if (maxCachedTypes <= 0) {
            throw new IllegalArgumentException("maxCachedTypes must be positive, got: " + maxCachedTypes);
        }

        this.strategies = List.copyOf(strategies);
        this.unknownHandler = unknownHandler;
        this.maxCachedTypes = maxCachedTypes;
    }

    @NonNull
    public DispatchResult dispatch(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
        Class<? extends Exception> type = exception.getClass();

        TopExceptionHandlingStrategy cached = typeCache.get(type);
        if (cached != null) {
            return apply(cached, exception, context);
        }

        for (TopExceptionHandlingStrategy strategy : strategies) {
            if (strategy.supports(exception)) {
                cacheIfRoom(type, strategy);
                return apply(strategy, exception, context);
            }
        }

        ErrorEntry entry = unknownHandler.handle(exception, context);
        LogPolicy policy = unknownHandler.logPolicy(exception, entry);
        return new DispatchResult(entry, policy, UNKNOWN_STRATEGY_NAME, true);
    }

    @NonNull
    private static DispatchResult apply(@NonNull TopExceptionHandlingStrategy strategy,
                                        @NonNull Exception exception,
                                        @NonNull ErrorHandlingContext context) {
        ErrorEntry entry = strategy.handle(exception, context);
        LogPolicy policy = strategy.logPolicy(exception, entry);
        String className = strategy.getClass().getSimpleName();
        return new DispatchResult(entry, policy, className, false);
    }

    private void cacheIfRoom(@NonNull Class<? extends Exception> type,
                             @NonNull TopExceptionHandlingStrategy strategy) {
        if (typeCache.size() >= maxCachedTypes) {
            emitCapacityWarningOnce();
            return;
        }

        typeCache.putIfAbsent(type, strategy);
    }

    private void emitCapacityWarningOnce() {
        if (capacityWarningEmitted.compareAndSet(false, true)) {
            log.warn("Exception strategy type cache reached capacity ({}). "
                            + "New types will fall back to linear scan; this typically indicates "
                            + "dynamic generation of exception classes.",
                    maxCachedTypes);
        }
    }

    int cacheSize() {
        return typeCache.size();
    }

    public record DispatchResult(@NonNull ErrorEntry entry,
                                 @NonNull LogPolicy logPolicy,
                                 @NonNull String strategyName,
                                 boolean unhandled) {
    }
}
