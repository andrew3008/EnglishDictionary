package space.br1440.platform.errorhandling.testing;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.extension.*;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.model.ErrorEntry;

import java.util.List;

/**
 * Центральный value-object тестовой инфраструктуры.
 *
 * <p>Предоставляет реальные экземпляры стратегий, {@link CauseChainResolver},
 * {@link ErrorHandlingContext} и properties для тестов без Spring-контекста.
 * Хранит последний результат обработки для автоматической верификации через
 * {@code @ExpectErrorEntry}.
 */
public final class StrategyTestContext {

    private final List<TopExceptionHandlingStrategy> topStrategies;
    private final List<RootCauseExceptionHandlingStrategy> rootCauseStrategies;
    private final CauseChainResolver resolver;
    private final ErrorHandlingContext context;
    private final ErrorHandlingProperties properties;

    @Nullable
    private ErrorEntry lastResult;

    @Nullable
    private Throwable builtCauseChain;

    public StrategyTestContext(@NonNull List<TopExceptionHandlingStrategy> topStrategies,
                               @NonNull List<RootCauseExceptionHandlingStrategy> rootCauseStrategies,
                               @NonNull CauseChainResolver resolver,
                               @NonNull ErrorHandlingContext context,
                               @NonNull ErrorHandlingProperties properties) {
        this.topStrategies = List.copyOf(topStrategies);
        this.rootCauseStrategies = List.copyOf(rootCauseStrategies);
        this.resolver = resolver;
        this.context = context;
        this.properties = properties;
    }

    /**
     * Вызывает {@code strategy.handle(ex, context)} и сохраняет результат.
     */
    @NonNull
    public ErrorEntry handleWith(@NonNull TopExceptionHandlingStrategy strategy,
                                 @NonNull Exception exception) {
        ErrorEntry entry = strategy.handle(exception, context);
        this.lastResult = entry;
        return entry;
    }

    /**
     * Вызывает {@code strategy.extractCauseDetails(cause)} и возвращает результат.
     */
    @NonNull
    public RootCauseResolution extractCauseWith(@NonNull RootCauseExceptionHandlingStrategy strategy,
                                                @NonNull Throwable cause) {
        return strategy.extractCauseDetails(cause);
    }

    /**
     * Делегирует в {@link CauseChainResolver#resolve(Throwable)}.
     */
    @NonNull
    public ResolvedRootCause resolve(@NonNull Throwable root) {
        return resolver.resolve(root);
    }

    /**
     * Делегирует в {@link CauseChainResolver#resolveAll(Throwable)}.
     */
    @NonNull
    public List<ResolvedRootCause> resolveAll(@NonNull Throwable root) {
        return resolver.resolveAll(root);
    }

    @NonNull
    public List<TopExceptionHandlingStrategy> getTopStrategies() {
        return topStrategies;
    }

    @NonNull
    public List<RootCauseExceptionHandlingStrategy> getRootCauseStrategies() {
        return rootCauseStrategies;
    }

    @NonNull
    public CauseChainResolver getResolver() {
        return resolver;
    }

    @NonNull
    public ErrorHandlingContext getContext() {
        return context;
    }

    @NonNull
    public ErrorHandlingProperties getProperties() {
        return properties;
    }

    @Nullable
    public ErrorEntry getLastResult() {
        return lastResult;
    }

    public void setLastResult(@Nullable ErrorEntry lastResult) {
        this.lastResult = lastResult;
    }

    @Nullable
    public Throwable getBuiltCauseChain() {
        return builtCauseChain;
    }

    public void setBuiltCauseChain(@Nullable Throwable builtCauseChain) {
        this.builtCauseChain = builtCauseChain;
    }

}
