package space.br1440.platform.errorhandling.core.strategy.cause.integration;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;

import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.http.FeignExceptionUtils;
import space.br1440.platform.errorhandling.core.util.http.FeignExceptionUtils.RetryAfterInfo;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.util.ArrayList;
import java.util.List;

/**
 * Стратегия классификации исключений OpenFeign как первопричины бизнес-исключений.
 *
 * <p>Обрабатывает сценарий, когда {@code FeignException} является root cause
 * бизнес-исключения:
 * <pre>
 * PaymentSubmitException (AbstractErrorResponseException)
 *   └─ cause: FeignException.ServiceUnavailable (503 от payment-service)
 * </pre>
 *
 * <h2>HTTP-маппинг</h2>
 * <p>См. {@link FeignExceptionUtils#mapFeignStatusToHttpStatus(boolean, int)}.
 * Ключевые решения:
 * <ul>
 *   <li>400, 413, 414, 415, 422 → {@code 500 INTERNAL_SERVER_ERROR} —
 *       это наш баг (мы сформировали неверный запрос), не проблема интеграции.
 *       Маскирование под {@code 502} искажает SLO-дашборды: при росте 502 SRE
 *       ищет инфраструктуру, а причина в коде сервиса.</li>
 *   <li>401/403/404/410/429 — проксируются как есть.</li>
 *   <li>408 → {@code 504} (upstream timed out нас — мы тоже не успели).</li>
 *   <li>409 → {@code 409 CONFLICT} (бизнес-конфликт состояния, не интеграция).</li>
 *   <li>Прочие 4xx/5xx → {@code 502 BAD_GATEWAY}.</li>
 *   <li>{@code RetryableException} без HTTP-статуса → {@code 503 SERVICE_UNAVAILABLE}.</li>
 * </ul>
 */
public class FeignRootCauseExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final String DOMAIN = "http-integration";

    public FeignRootCauseExceptionStrategy(StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.FEIGN, stackHintExtractor);
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return FeignExceptionUtils.isFeignException(cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        boolean isRetryable = FeignExceptionUtils.isRetryable(cause);
        int feignStatus = FeignExceptionUtils.extractFeignStatus(cause);
        RetryAfterInfo retryAfter = FeignExceptionUtils.extractRetryAfterInfo(cause, isRetryable);

        HttpStatus httpStatus = FeignExceptionUtils.mapFeignStatusToHttpStatus(isRetryable, feignStatus);
        String reason = FeignExceptionUtils.determineErrorCode(httpStatus);

        List<ErrorDetail> details = new ArrayList<>(2);
        if (isRetryable) {
            details.add(new RetryInfoDetail(retryAfter.delay()));
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(reason)
                .domain(DOMAIN)
                .diagnosticMetadata(
                        FeignExceptionUtils.buildDiagnosticMetadata(
                                cause, feignStatus, retryAfter, stackHintExtractor
                        )
                )
                .build();
        details.add(info);

        String message = resolveMessage(httpStatus);
        return isRetryable
                ? RootCauseResolution.retryable(httpStatus, message, details)
                : RootCauseResolution.notRetryable(httpStatus, message, details);
    }

    @NonNull
    private static String resolveMessage(@NonNull HttpStatus httpStatus) {
        return switch (httpStatus) {
            case UNAUTHORIZED -> "The upstream service rejected authentication credentials.";
            case FORBIDDEN -> "The upstream service denied access for the requested operation.";
            case NOT_FOUND -> "The requested upstream resource was not found.";
            case GONE -> "The requested upstream resource is no longer available.";
            case CONFLICT -> "The request conflicts with the current state of the upstream resource.";
            case TOO_MANY_REQUESTS -> "The upstream service rate limit was exceeded. Retry later.";
            case GATEWAY_TIMEOUT -> "The upstream service did not respond within the expected time window.";
            case BAD_GATEWAY -> "The upstream service returned an unexpected response.";
            case SERVICE_UNAVAILABLE -> "The upstream service is currently unavailable.";
            case INTERNAL_SERVER_ERROR -> "The request to the upstream service was malformed.";
            default -> "An upstream integration error occurred.";
        };
    }
}
