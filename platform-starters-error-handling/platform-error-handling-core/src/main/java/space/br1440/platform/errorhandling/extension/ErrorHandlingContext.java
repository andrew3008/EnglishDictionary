package space.br1440.platform.errorhandling.extension;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.http.UpstreamOutcome;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

import java.util.Objects;
import java.util.Optional;

/**
 * Контекст выполнения цикла обработки исключений.
 *
 * <p>Передаётся в каждый SPI-вызов ({@link TopExceptionHandlingStrategy},
 * {@link ErrorEntryEnricher}, {@link UnknownExceptionHandler}) и предоставляет
 * разработчику микросервиса доступ к конфигурации стартера и метаданным запроса.
 *
 * <p>Содержит {@link StackHintExtractor} для извлечения стек-хинтов —
 * top-level стратегии получают экстрактор через {@link #getStackHintExtractor()}.
 *
 * <h2>{@link UpstreamOutcome}</h2>
 * <p>Ответ прямого upstream-сервиса ({@link #getUpstreamOutcome()}) появляется
 * в пайплайне только после того, как top- и cause-стратегии уже отработали —
 * тело ответа разбирается отдельно и один раз (см. {@code UpstreamResponseReader}).
 * Поэтому исходный {@code of(...)} его не принимает: производный экземпляр
 * создаётся через {@link #withUpstreamOutcome(UpstreamOutcome)} и виден только
 * {@link ErrorEntryEnricher}. Стратегии {@link UpstreamOutcome} не видят.
 *
 * @see TopExceptionHandlingStrategy
 * @see ErrorEntryEnricher
 * @see UnknownExceptionHandler
 */
public final class ErrorHandlingContext {

    private final ErrorHandlingProperties properties;
    private final String requestPath;
    private final StackHintExtractor stackHintExtractor;
    private final Optional<UpstreamOutcome> upstreamOutcome;

    private ErrorHandlingContext(@NonNull ErrorHandlingProperties properties,
                                 @Nullable String requestPath,
                                 @NonNull StackHintExtractor stackHintExtractor,
                                 @NonNull Optional<UpstreamOutcome> upstreamOutcome) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.requestPath = requestPath;
        this.stackHintExtractor = Objects.requireNonNull(stackHintExtractor, "stackHintExtractor must not be null");
        this.upstreamOutcome = Objects.requireNonNull(upstreamOutcome, "upstreamOutcome must not be null");
    }

    @NonNull
    public static ErrorHandlingContext of(@NonNull ErrorHandlingProperties properties,
                                          @Nullable String requestPath,
                                          @NonNull StackHintExtractor stackHintExtractor) {
        return new ErrorHandlingContext(properties, requestPath, stackHintExtractor, Optional.empty());
    }

    @NonNull
    public ErrorHandlingContext withUpstreamOutcome(@NonNull UpstreamOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        return new ErrorHandlingContext(properties, requestPath, stackHintExtractor, Optional.of(outcome));
    }

    @NonNull
    public ErrorHandlingProperties getProperties() {
        return properties;
    }

    @Nullable
    public String getRequestPath() {
        return requestPath;
    }

    @NonNull
    public StackHintExtractor getStackHintExtractor() {
        return stackHintExtractor;
    }

    @NonNull
    public Optional<UpstreamOutcome> getUpstreamOutcome() {
        return upstreamOutcome;
    }
}
