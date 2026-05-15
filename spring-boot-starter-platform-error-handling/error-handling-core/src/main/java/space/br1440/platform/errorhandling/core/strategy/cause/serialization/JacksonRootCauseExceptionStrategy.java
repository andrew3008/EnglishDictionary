package space.br1440.platform.errorhandling.core.strategy.cause.serialization;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.core.strategy.top.BusinessExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.JacksonExceptionUtils;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;

/**
 * Стратегия классификации {@code JsonProcessingException} как первопричины.
 *
 * <p>Реализует {@link RootCauseExceptionHandlingStrategy} для классификации ошибок JSON-обработки,
 * скрытых внутри бизнес-исключений. Это актуально, когда JSON-обработка происходит
 * внутри бизнес-логики (кэш, event store, messaging, ручные вызовы {@code ObjectMapper}).
 *
 * <p>Позволяет {@link BusinessExceptionStrategy} классифицировать ошибки JSON-обработки
 * и обогащать ответ диагностическими метаданными.
 *
 * <h3>Диагностические метаданные</h3>
 * <p>Полный перечень диагностических полей — см.
 * {@link JacksonExceptionUtils} class-level Javadoc.
 * Ключевые поля:
 * <ul>
 *   <li>{@code exceptionClass} — FQCN типа исключения</li>
 *   <li>{@code format} — {@code "JSON"}</li>
 *   <li>{@code causeMessage} — оригинальное сообщение без location appendix
 *       (через {@code getOriginalMessage()}, truncated)</li>
 *   <li>{@code targetType} — FQCN целевого класса</li>
 *   <li>{@code location} — строка/колонка в JSON-документе</li>
 *   <li>{@code byteOffset} — для бинарных форматов (CBOR, Smile, Ion)</li>
 *   <li>{@code path} — JSON-путь в точечно-индексной нотации</li>
 *   <li>{@code pathDepth} — глубина вложенности JSON-пути</li>
 *   <li>{@code knownProperties} — допустимые свойства (для {@code UnrecognizedPropertyException})</li>
 *   <li>{@code typeId} / {@code baseType} — (для {@code InvalidTypeIdException})</li>
 * </ul>
 */
public class JacksonRootCauseExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    public JacksonRootCauseExceptionStrategy(StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.JACKSON, stackHintExtractor);
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return cause instanceof JsonProcessingException;
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        return JacksonExceptionUtils.buildCauseDetails((JsonProcessingException) cause, stackHintExtractor);
    }
}
