package space.br1440.platform.errorhandling.core.strategy.top;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.PropertyBindingException;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.base.AbstractTopExceptionStrategy;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.core.util.JacksonExceptionUtils;
import space.br1440.platform.errorhandling.extension.order.TopExceptionStrategyOrder;

/**
 * Стратегия обработки ошибок сериализации/десериализации Jackson вне MVC-слоя.
 *
 * <p>Закрывает пробел между {@code SpringMvcExceptionStrategy}, которая перехватывает
 * Jackson-ошибки через Spring-обёртки ({@code HttpMessageNotReadableException},
 * {@code HttpMessageNotWritableException}) в HTTP request/response pipeline,
 * и остальными слоями приложения: messaging-consumers, cache (Redis/Hazelcast),
 * event store, ручные вызовы {@code ObjectMapper.readValue()} / {@code writeValueAsString()}.
 *
 * <h2>Разграничение с {@code SpringMvcExceptionStrategy}</h2>
 * <p>
 * Spring MVC оборачивает Jackson-ошибки при десериализации тела HTTP-запроса в
 * {@code HttpMessageNotReadableException}, а при сериализации ответа — в
 * {@code HttpMessageNotWritableException}. Прямые {@code JsonProcessingException},
 * возникшие вне этих точек, до {@code SpringMvcExceptionStrategy} не доходят
 * и обрабатываются данной стратегией.
 *
 * <h2>Cause-Aware Enrichment</h2>
 * <p>Если {@code JsonProcessingException} оборачивает техническую первопричину,
 * стратегия:
 * <ol>
 *   <li>Находит <b>самое глубокое</b> исключение в cause chain
 *       (аналог {@code ExceptionUtils.getRootCause()} из {@code nc-error-handling})</li>
 *   <li>Для этого исключения ищет подходящую {@code RootCauseExceptionHandlingStrategy} стратегию</li>
 * </ol>
 *
 * <h2>HTTP-семантика</h2>
 * <ul>
 *   <li><b>400 Bad Request</b> — невалидный входящий payload (ошибка отправителя):
 *     <ul>
 *       <li>{@link JsonParseException} — синтаксически некорректный JSON;</li>
 *       <li>{@link PropertyBindingException} / {@code UnrecognizedPropertyException} —
 *           неизвестное или недопустимое поле (обрабатывается первым, т.к.
 *           {@code PropertyBindingException extends MismatchedInputException});</li>
 *       <li>{@link MismatchedInputException} — тип/структура поля не соответствует схеме;</li>
 *       <li>{@link JsonMappingException} (прочие подклассы) — общая ошибка маппинга
 *           при десериализации.</li>
 *     </ul>
 *   </li>
 *   <li><b>500 Internal Server Error</b> — ошибка на нашей стороне:
 *     <ul>
 *       <li>{@link InvalidDefinitionException} — неправильная конфигурация
 *           {@code ObjectMapper}: отсутствующий конструктор, неизвестный тип в иерархии
 *           {@code @JsonTypeInfo}, некорректная аннотация;</li>
 *       <li>{@link JsonGenerationException} — ошибка генерации при сериализации
 *           исходящих данных (нарушение sequence записи).</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h2>Диагностические метаданные</h2>
 * <p>Полный перечень диагностических полей — см.
 * {@link JacksonExceptionUtils} class-level Javadoc.
 * Ключевые поля: {@code exceptionClass} (FQCN), {@code format} ({@code "JSON"}),
 * {@code causeMessage} (оригинальное сообщение, truncated), {@code targetType} (FQCN),
 * {@code location}, {@code byteOffset} (бинарные форматы), {@code path},
 * {@code pathDepth}, {@code knownProperties} ({@code UnrecognizedPropertyException}),
 * {@code typeId} / {@code baseType} ({@code InvalidTypeIdException}).
 */
public class JacksonExceptionStrategy extends AbstractTopExceptionStrategy {

    public JacksonExceptionStrategy(@NonNull CauseChainResolver causeChainResolver) {
        super(TopExceptionStrategyOrder.JACKSON, causeChainResolver);
    }

    @Override
    @NonNull
    public LogPolicy logPolicy(@NonNull Exception exception, @NonNull ErrorEntry entry) {
        return entry.getHttpStatus().is5xxServerError() ? LogPolicy.ERROR : LogPolicy.WARN;
    }

    @Override
    public boolean supports(@NonNull Exception exception) {
        return exception instanceof JsonProcessingException;
    }

    @Override
    @NonNull
    protected ErrorEntry doHandle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
        JsonProcessingException jpe = (JsonProcessingException) exception;
        RootCauseResolution baseCause = JacksonExceptionUtils.buildCauseDetails(jpe, context.getStackHintExtractor());
        return createBaseEntry(baseCause, jpe, context);
    }

    @NonNull
    private static ErrorEntry createBaseEntry(@NonNull RootCauseResolution RootCauseResolution,
                                              @NonNull JsonProcessingException source,
                                              @NonNull ErrorHandlingContext context) {
        HttpStatus httpStatus = RootCauseResolution.httpStatus();
        String errorCode = httpStatus.is5xxServerError() ? "INTERNAL" : "INVALID_ARGUMENT";

        return ErrorEntry.builder()
                .httpStatus(httpStatus)
                .errorCode(errorCode)
                .reason(source.getClass().getName())
                .message(RootCauseResolution.message())
                .requestURI(context.getRequestPath())
                .details(RootCauseResolution.details())
                .build();
    }
}
