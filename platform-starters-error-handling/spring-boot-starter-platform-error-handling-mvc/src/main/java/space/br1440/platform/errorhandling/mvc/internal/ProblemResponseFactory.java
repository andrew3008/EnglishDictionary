package space.br1440.platform.errorhandling.mvc.internal;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.core.util.RetryabilityResolver;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.http.ErrorMediaTypes;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.RequestContext;
import space.br1440.platform.errorhandling.model.dto.ErrorEntryDTO;
import space.br1440.platform.errorhandling.model.error_detail.dto.ErrorInfoDetailDTO;
import space.br1440.platform.errorhandling.model.mapping.ErrorEntryToDtoMapper;

import java.util.Map;

/**
 * Сборка финального HTTP-ответа в формате {@code application/problem+json}.
 *
 * <h2>Состав ответа</h2>
 * <ul>
 *   <li>HTTP-статус — из {@link ErrorEntry#getHttpStatus()}.</li>
 *   <li>Content-Type: {@code application/problem+json;version=1}.</li>
 *   <li>Заголовок {@code Retry-After} — если в entry есть {@code RetryInfoDetail}
 *       с положительной задержкой.</li>
 *   <li>Дозаполнение {@code domain} (из {@code applicationName}) и {@code requestURI},
 *       если они не были заданы стратегией.</li>
 * </ul>
 *
 * <p>Заголовок {@code X-Correlation-Id} эта фабрика <b>не пишет</b> — в joint-режиме
 * им владеет tracing identity boundary, поэтому решение echo принимает вызывающий
 * ({@code GlobalExceptionHandler}), а не фабрика (см. {@code adr-correlation-ownership.md}).
 *
 * <h2>Safety-net</h2>
 * <p>{@link #safetyNet(String)} формирует минимально валидный 500-ответ для случая,
 * когда основной pipeline сам выбросил исключение. Тело строится через тот же
 * {@link ErrorEntryToDtoMapper} — это исключает дрейф формата safety-net JSON
 * относительно основного pipeline (которое было риском при хардкоженных JSON-шаблонах).
 */
public final class ProblemResponseFactory {

    public static final String SAFETY_NET_MESSAGE = "An unexpected error occurred";
    public static final String SAFETY_NET_ERROR_CODE = "INTERNAL";
    public static final String SAFETY_NET_GENERIC_DOMAIN = "internal";

    private final ErrorEntryToDtoMapper mapper;
    private final String applicationName;
    private final boolean exposeApplicationNameInSafetyNet;

    public ProblemResponseFactory(@NonNull ErrorEntryToDtoMapper mapper,
                                  @NonNull String applicationName,
                                  @NonNull ErrorHandlingProperties properties) {
        this.mapper = mapper;
        this.applicationName = applicationName;
        this.exposeApplicationNameInSafetyNet = properties.getSafetyNet().isExposeApplicationName();
    }

    @NonNull
    public ResponseEntity<ErrorEntryDTO> build(@NonNull ErrorEntry entry,
                                               @NonNull String correlationId,
                                               @NonNull String requestUri) {
        ErrorEntry finalEntry = withDefaults(entry, correlationId, requestUri);

        ResponseEntity.BodyBuilder bodyBuilder = ResponseEntity
                .status(finalEntry.getHttpStatus())
                .contentType(ErrorMediaTypes.ERROR_RESPONSE_MEDIA_TYPE);

        long retryDelaySeconds = RetryabilityResolver.retryDelayHeaderSeconds(finalEntry);
        if (retryDelaySeconds > 0) {
            bodyBuilder.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryDelaySeconds));
        }

        ErrorEntryDTO dto = mapper.toDto(finalEntry);
        return bodyBuilder.body(dto);
    }

    /**
     * Safety-net ответ: используется при критическом сбое самого pipeline обработки.
     */
    @NonNull
    public ResponseEntity<ErrorEntryDTO> safetyNet(@NonNull String correlationId) {
        String safetyDomain = exposeApplicationNameInSafetyNet
                ? applicationName
                : SAFETY_NET_GENERIC_DOMAIN;

        ErrorEntryDTO error = ErrorEntryDTO.builder()
                .httpCode(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .errorCode(SAFETY_NET_ERROR_CODE)
                .message(SAFETY_NET_MESSAGE)
                .domain(safetyDomain)
                .metadata(Map.of("correlationId", correlationId))
                .detail(ErrorInfoDetailDTO.builder()
                        .reason(SAFETY_NET_ERROR_CODE)
                        .domain(safetyDomain)
                        .build())
                .build();

        return ResponseEntity.internalServerError()
                .contentType(ErrorMediaTypes.ERROR_RESPONSE_MEDIA_TYPE)
                .body(error);
    }

    @NonNull
    private ErrorEntry withDefaults(@NonNull ErrorEntry entry,
                                    @NonNull String correlationId,
                                    @NonNull String requestUri) {
        RequestContext requestContext = (entry.getRequestContext() != null)
                ? entry.getRequestContext().withCorrelationIdIfMissing(correlationId)
                : RequestContext.builder().correlationId(correlationId).build();

        ErrorEntry.ErrorEntryBuilder builder = entry.toBuilder().requestContext(requestContext);
        if (!StringUtils.hasText(entry.getDomain())) {
            builder.domain(applicationName);
        }

        if (!StringUtils.hasText(entry.getRequestURI())) {
            builder.requestURI(requestUri);
        }

        return builder.build();
    }
}
