package space.br1440.platform.errorhandling.core.strategy.top;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.base.AbstractTopExceptionStrategy;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.core.util.http.FeignExceptionUtils;
import space.br1440.platform.errorhandling.core.util.http.UpstreamIntegrationDomain;
import space.br1440.platform.errorhandling.extension.order.TopExceptionStrategyOrder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Стратегия обработки ошибок OpenFeign-клиентов (top-level exception).
 *
 * <p>Перехватывает {@code feign.FeignException} и {@code feign.RetryableException},
 * возникающие при вызове upstream-сервисов через декларативные Feign-клиенты
 * (Spring Cloud OpenFeign).
 *
 * <h2>HTTP-семантика</h2>
 * <ul>
 *   <li><b>401 Unauthorized</b> — upstream отверг запрос из-за ошибки аутентификации.</li>
 *   <li><b>403 Forbidden</b> — upstream отверг запрос из-за недостаточных прав.</li>
 *   <li><b>404 Not Found</b> — ресурс не найден в upstream-сервисе (404 или 410 Gone).</li>
 *   <li><b>429 Too Many Requests</b> — upstream ограничил скорость запросов.</li>
 *   <li><b>502 Bad Gateway</b> — некорректный запрос к upstream (4xx) либо upstream
 *       вернул неожиданную 5xx ошибку. Исключение — 503 и 504.</li>
 *   <li><b>503 Service Unavailable</b> — upstream временно недоступен,
 *       либо {@code RetryableException} после исчерпания попыток.</li>
 *   <li><b>504 Gateway Timeout</b> — upstream не ответил в течение таймаута.</li>
 * </ul>
 *
 * <h2>Dual-metadata контракт</h2>
 * <p>{@code clientMetadata} — публичные данные (возвращаются клиенту):
 * <ul>
 *   <li>{@code upstreamStatus} — HTTP-статус ответа upstream-сервиса.</li>
 * </ul>
 *
 * <p>{@code diagnosticMetadata} — production-grade диагностика для SRE/developers
 * (не включается в DTO). Полный список полей — см. {@link FeignExceptionUtils}.
 *
 * <h2>Безопасность (CWE-209)</h2>
 * <p>
 * Сообщение {@code FeignException.getMessage()} содержит URL upstream-сервиса и тело ответа —
 * потенциальная утечка инфраструктурных деталей. Данная стратегия <b>не включает</b>
 * его в ответ клиенту. URL query params, response body и Authorization headers
 * не включаются в diagnostic metadata.
 */
public class FeignExceptionStrategy extends AbstractTopExceptionStrategy {

    public FeignExceptionStrategy(@NonNull CauseChainResolver causeChainResolver) {
        super(TopExceptionStrategyOrder.FEIGN, causeChainResolver);
    }

    /**
     * Политика логирования:
     * <ul>
     *   <li>{@code 5xx} (502, 503, 504) → {@link LogPolicy#ERROR}: upstream недоступен
     *       или интеграция сломана — требует расследования.</li>
     *   <li>{@code 4xx} (401, 403, 404, 429) → {@link LogPolicy#WARN}: ожидаемые
     *       клиентские/авторизационные сценарии.</li>
     * </ul>
     */
    @Override
    @NonNull
    public LogPolicy logPolicy(@NonNull Exception exception, @NonNull ErrorEntry entry) {
        return entry.getHttpStatus().is5xxServerError() ? LogPolicy.ERROR : LogPolicy.WARN;
    }

    @Override
    public boolean supports(@NonNull Exception exception) {
        return FeignExceptionUtils.isFeignException(exception);
    }

    @Override
    @NonNull
    protected ErrorEntry doHandle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
        boolean isRetryable = FeignExceptionUtils.isRetryable(exception);
        int feignStatus = FeignExceptionUtils.extractFeignStatus(exception);
        HttpStatus httpStatus = FeignExceptionUtils.mapFeignStatusToHttpStatus(isRetryable, feignStatus);

        Map<String, String> clientMetadata = new LinkedHashMap<>();
        if (feignStatus > 0) {
            clientMetadata.put("upstreamStatus", Integer.toString(feignStatus));
        }

        Map<String, String> diagnosticMetadata = FeignExceptionUtils.buildDiagnosticMetadata(exception, context.getStackHintExtractor());

        ErrorEntry entry = createFeignEntry(httpStatus, feignStatus, clientMetadata, diagnosticMetadata, context);

        if (isRetryable) {
            entry = entry.toBuilder()
                    .detail(new RetryInfoDetail())
                    .build();
        }

        return entry;
    }

    @NonNull
    private static ErrorEntry createFeignEntry(@NonNull HttpStatus httpStatus,
                                        int feignStatus,
                                        @NonNull Map<String, String> clientMeta,
                                        @NonNull Map<String, String> diagnosticMeta,
                                        @NonNull ErrorHandlingContext context) {
        return switch (httpStatus) {
            case UNAUTHORIZED -> createEntry(
                    HttpStatus.UNAUTHORIZED,
                    "The upstream service rejected the request due to an authentication failure.",
                    clientMeta, diagnosticMeta, context
            );

            case FORBIDDEN -> createEntry(
                    HttpStatus.FORBIDDEN,
                    "The upstream service rejected the request due to insufficient permissions.",
                    clientMeta, diagnosticMeta, context
            );

            case NOT_FOUND -> createEntry(
                    HttpStatus.NOT_FOUND,
                    "The requested resource was not found in the upstream service.",
                    clientMeta, diagnosticMeta, context
            );

            case TOO_MANY_REQUESTS -> createEntry(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "The upstream service has rejected the request due to a rate limit. Retry after a short delay.",
                    clientMeta, diagnosticMeta, context
            );

            case SERVICE_UNAVAILABLE -> createEntry(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "The upstream service is temporarily unavailable. Retry after a short delay.",
                    clientMeta, diagnosticMeta, context
            );

            case GATEWAY_TIMEOUT -> createEntry(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "The upstream service did not respond within the configured timeout.",
                    clientMeta, diagnosticMeta, context
            );

            default -> {
                String message = (feignStatus >= 500)
                        ? "The upstream service returned an unexpected error."
                        : "The upstream service rejected the request.";
                yield createEntry(HttpStatus.BAD_GATEWAY, message, clientMeta, diagnosticMeta, context);
            }
        };
    }

    @NonNull
    private static ErrorEntry createEntry(HttpStatus httpStatus,
                                          @NonNull String message,
                                          @NonNull Map<String, String> clientMeta,
                                          @NonNull Map<String, String> diagnosticMeta,
                                          @NonNull ErrorHandlingContext context) {
        ErrorInfoDetail.ErrorInfoDetailBuilder infoBuilder = ErrorInfoDetail.builder()
                .reason(httpStatus.getReasonPhrase())
                .domain(UpstreamIntegrationDomain.HTTP_INTEGRATION);

        if (!clientMeta.isEmpty()) {
            infoBuilder.clientMetadata(Collections.unmodifiableMap(new LinkedHashMap<>(clientMeta)));
        }

        if (!diagnosticMeta.isEmpty()) {
            infoBuilder.diagnosticMetadata(diagnosticMeta);
        }

        ErrorInfoDetail info = infoBuilder.build();
        return ErrorEntry.builder()
                .httpStatus(httpStatus)
                .errorCode(FeignExceptionUtils.determineErrorCode(httpStatus))
                .message(message)
                .requestURI(context.getRequestPath())
                .details(List.of(info))
                .build();
    }
}
