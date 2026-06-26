package space.br1440.platform.errorhandling.extension;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

import java.util.Objects;

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
 * @see TopExceptionHandlingStrategy
 * @see ErrorEntryEnricher
 * @see UnknownExceptionHandler
 */
public final class ErrorHandlingContext {

    private final ErrorHandlingProperties properties;
    private final String requestPath;
    private final StackHintExtractor stackHintExtractor;

    private ErrorHandlingContext(@NonNull ErrorHandlingProperties properties,
                                 @Nullable String requestPath,
                                 @NonNull StackHintExtractor stackHintExtractor) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.requestPath = requestPath;
        this.stackHintExtractor = Objects.requireNonNull(stackHintExtractor, "stackHintExtractor must not be null");
    }

    @NonNull
    public static ErrorHandlingContext of(@NonNull ErrorHandlingProperties properties,
                                          @Nullable String requestPath,
                                          @NonNull StackHintExtractor stackHintExtractor) {
        return new ErrorHandlingContext(properties, requestPath, stackHintExtractor);
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

}
