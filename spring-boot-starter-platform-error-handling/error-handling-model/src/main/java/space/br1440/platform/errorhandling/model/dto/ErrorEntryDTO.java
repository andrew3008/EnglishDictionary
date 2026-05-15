package space.br1440.platform.errorhandling.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import space.br1440.platform.errorhandling.model.error_detail.dto.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Стандартизированная модель ответа об ошибке REST API (AIP-193 aligned).
 *
 * <h2>Дизайн</h2>
 * <p>
 * Модель на основе <a href="https://google.aip.dev/193">Google AIP-193</a>:
 * <ul>
 *   <li>{@code httpCode} — числовой HTTP-статус ответа</li>
 *   <li>{@code errorCode} — машиночитаемый код top-exception (бизнес/wrapper),
 *       стабильный между релизами; используется клиентом для классификации ошибки</li>
 *   <li>{@code message} — человекочитаемое описание ошибки top-exception (без технических суффиксов)</li>
 *   <li>{@code reason} — человекочитаемое сообщение root-cause (или {@code null}, если cause нет).
 *       Машиночитаемый код root-cause находится в {@code details[].ERROR_INFO.reason}</li>
 *   <li>{@code domain} — namespace сервиса</li>
 *   <li>{@code metadata} — клиентские метаданные top-exception, объединённые с {@code RequestContext}
 *       ({@code correlationId}, {@code traceId}, {@code spanId})</li>
 *   <li>{@code details[]} — полиморфный список деталей: только resolved cause-цепочка
 *       ({@code ERROR_INFO} для каждой причины) + не-{@code ERROR_INFO} типы
 *       ({@code BAD_REQUEST}, {@code RETRY_INFO} и т.д.). <b>Top-{@code ERROR_INFO}
 *       сюда не дублируется</b> — он целиком представлен top-level полями
 *       {@code errorCode} / {@code domain} / {@code metadata}, что соответствует
 *       контракту {@code google.rpc.ErrorInfo} (single ErrorInfo per error).</li>
 * </ul>
 *
 * <h2>Поле {@code details[]}</h2>
 * <p>
 * Полиморфный список деталей ошибки. Каждый элемент — подтип {@link ErrorDetailDTO},
 * идентифицируемый по полю {@code type}:
 * <ul>
 *   <li>{@code BAD_REQUEST} → {@link BadRequestDetailDTO}</li>
 *   <li>{@code PRECONDITION_FAILURE} → {@link PreconditionFailureDetailDTO}</li>
 *   <li>{@code ERROR_INFO} → {@link ErrorInfoDetailDTO} — машиночитаемый {@code reason} + {@code domain}</li>
 *   <li>{@code RESOURCE_INFO} → {@link ResourceInfoDetailDTO}</li>
 *   <li>{@code HELP} → {@link HelpDetailDTO}</li>
 *   <li>{@code RETRY_INFO} → {@link RetryInfoDetailDTO}</li>
 * </ul>
 *
 * <h2>Пример JSON-ответа (500 — бизнес-ошибка с upstream root cause)</h2>
 * <pre>{@code
 * {
 *   "httpCode": 500,
 *   "errorCode": "CALCULATION_PERSISTENCE",
 *   "message": "Failed to persist calculation result for calc-42",
 *   "reason": "External service HarborRegistry returned an error: Unexpected response status: 500",
 *   "domain": "ballistics-service",
 *   "requestURI": "/api/calculations/calc-42/persist",
 *   "metadata": {
 *     "calculationId": "calc-42",
 *     "correlationId": "abc-123"
 *   },
 *   "details": [
 *     {
 *       "type": "ERROR_INFO",
 *       "reason": "EXTERNAL_SERVICE",
 *       "domain": "ballistics-service",
 *       "metadata": { "serviceName": "HarborRegistry", "errorDescription": "Unexpected response status: 500" }
 *     },
 *     {
 *       "type": "RETRY_INFO",
 *       "retryDelay": "PT30S"
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <h2>Пример JSON-ответа (400 — одиночная ошибка валидации без cause)</h2>
 * <pre>{@code
 * {
 *   "httpCode": 400,
 *   "errorCode": "BUSINESS_VALIDATION",
 *   "message": "Validation failed",
 *   "domain": "order-service",
 *   "requestURI": "/api/orders",
 *   "metadata": { "violationCount": "1", "correlationId": "abc-123" },
 *   "details": [
 *     {
 *       "type": "BAD_REQUEST",
 *       "fieldViolations": [
 *         { "field": "email", "reason": "INVALID_FORMAT", "description": "must be a valid email" }
 *       ]
 *     }
 *   ]
 * }
 * }</pre>
 *
 * @see <a href="https://google.aip.dev/193">AIP-193: Errors</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9457">RFC 9457 Problem Details</a>
 */
@Value
@Builder(toBuilder = true, buildMethodName = "internalBuild")
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"type", "httpCode", "errorCode", "message", "reason", "domain", "requestURI", "metadata", "details"})
public class ErrorEntryDTO {

    /**
     * Минимальный валидный HTTP-статус (RFC 9110 §15).
     */
    private static final int HTTP_CODE_MIN = 100;

    /**
     * Максимальный валидный HTTP-статус (RFC 9110 §15).
     */
    private static final int HTTP_CODE_MAX = 599;

    /**
     * Дефолт RFC 9457 §4.1: {@code "about:blank"} означает «нет специфичного problem-type'а,
     * семантика ошибки уже передана HTTP-статусом».
     */
    public static final String TYPE_DEFAULT = "about:blank";

    /**
     * RFC 9457 §3.1 — URI идентификации problem type. По дефолту {@code about:blank}
     * (RFC 9457 §4.1: «no specific problem type, only HTTP status»). Сервисы могут
     * переопределить на собственный URI каталога ошибок (например,
     * {@code https://errors.example.com/oauth2/invalid-token}) для машинного роутинга
     * в UI или ссылок на runbook'и.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc9457#section-3.1">RFC 9457 §3.1</a>
     */
    @NotNull
    @Builder.Default
    String type = TYPE_DEFAULT;

    /**
     * Числовой HTTP-статус ответа (например, {@code 400}, {@code 404}, {@code 503}).
     */
    int httpCode;

    /**
     * Машиночитаемый код ошибки top-exception (бизнес/wrapper).
     * <p>
     * Стабилен между релизами; используется клиентом для классификации ошибки
     * и роутинга в UI. Формируется автоматически из имени concrete-исключения
     * (например, {@code CalculationPersistenceException} -> {@code "CALCULATION_PERSISTENCE"}).
     * <p>
     * Машиночитаемый код root-cause (если есть) — в {@code details[].ERROR_INFO.reason}.
     */
    @NotNull
    String errorCode;

    /**
     * Человекочитаемое описание ошибки top-exception.
     * <p>
     * Без технических суффиксов вида {@code [errorCode=...]} — машиночитаемый
     * идентификатор отдельным полем {@link #errorCode}.
     */
    @NotNull
    String message;

    /**
     * Человекочитаемое сообщение root-cause (если cause присутствует), либо {@code null}.
     * <p>
     * Источник — {@code resolution().message()} от RootCause-стратегии (приоритет, санитизировано),
     * либо {@code rootCause.getMessage()} как fallback.
     * <p>
     * Машиночитаемый код root-cause — в {@code details[].ERROR_INFO.reason}.
     */
    @Nullable
    String reason;

    /**
     * Домен ошибки — имя сервиса или платформы.
     * Например: {@code "order-service"}, {@code "billing-service"}.
     */
    String domain;

    /**
     * URI HTTP-запроса, при котором возникла ошибка (например, {@code "/api/orders/42"}).
     * <p>
     * Используется для корреляции ответа с access-логами (по паре
     * {@code requestURI} + {@code metadata.correlationId}).
     * <p>
     * Семантически близко к RFC 9457 §3.5 {@code instance}, но сознательно использует имя
     * {@code requestURI}: поле содержит URI запроса (контекст), а не URI конкретного
     * инцидента. Сериализуется как есть, без relativization относительно {@code type}.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc9457#section-3.5">RFC 9457 §3.5</a>
     */
    @Nullable
    String requestURI;

    /**
     * Клиентские метаданные top-exception, объединённые с {@code RequestContext}
     * ({@code correlationId}, {@code traceId}, {@code spanId}).
     * <p>
     * Приоритет ключей — за пользовательскими ({@code clientMetadata} имеет приоритет
     * над {@code RequestContext} при коллизии).
     *
     * @see <a href="https://cloud.google.com/apis/design/errors#error_info">google.rpc.ErrorInfo.metadata</a>
     */
    @NotNull
    @Builder.Default
    Map<String, String> metadata = Collections.emptyMap();

    /**
     * Структурированные детали ошибки ({@code google.rpc.Status.details}).
     * <p>
     * Содержит:
     * <ul>
     *   <li>{@code ERROR_INFO} для каждой <b>resolved cause</b> (root-cause + промежуточные)
     *       — но <b>не для top-exception</b>;</li>
     *   <li>прочие detail-типы: {@code BAD_REQUEST}, {@code RETRY_INFO},
     *       {@code RESOURCE_INFO}, {@code HELP}, {@code PRECONDITION_FAILURE}, {@code CUSTOM}.</li>
     * </ul>
     *
     * <p><b>Top-{@code ERROR_INFO} здесь не дублируется.</b>
     * Машиночитаемый код, домен и клиентские метаданные top-exception целиком представлены
     * top-level полями {@link #errorCode} / {@link #domain} / {@link #metadata}.
     * Это согласуется с контрактом {@code google.rpc.ErrorInfo}, в котором ErrorInfo
     * существует только в {@code Status.details}, без top-level дубля
     * (см. <a href="https://google.aip.dev/193">AIP-193</a>).
     * <p>
     * Маппер {@link space.br1440.platform.errorhandling.model.mapping.ErrorEntryToDtoMapper}
     * пропускает первый {@code ErrorInfoDetail} из {@code ErrorEntry.details} (по конвенции
     * это «own/header» текущего сервиса, синтезированный фабрикой) и сохраняет порядок
     * остальных деталей.
     */
    @NotNull
    @Singular
    List<ErrorDetailDTO> details;

    /**
     * Кастомный публичный {@code build()}-wrapper над Lombok-сгенерированным
     * {@code internalBuild()}: fail-fast валидация инвариантов перед созданием DTO.
     */
    public static class ErrorEntryDTOBuilder {

        public ErrorEntryDTO build() {
            validateHttpCode();
            validateRequiredFields();
            return internalBuild();
        }

        private void validateHttpCode() {
            if (httpCode < HTTP_CODE_MIN || httpCode > HTTP_CODE_MAX) {
                throw new IllegalStateException(
                        "ErrorEntryDTO.httpCode must be a valid HTTP status code ["
                                + HTTP_CODE_MIN + ".." + HTTP_CODE_MAX + "], got: " + httpCode);
            }
        }

        private void validateRequiredFields() {
            Objects.requireNonNull(errorCode,
                    "ErrorEntryDTO.errorCode must not be null — set a stable machine-readable code before build()");

            Objects.requireNonNull(message,
                    "ErrorEntryDTO.message must not be null — set a human-readable message before build()");
        }
    }
}
