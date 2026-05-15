package space.br1440.platform.errorhandling.core.strategy.cause;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import space.br1440.platform.errorhandling.core.strategy.top.WebFluxExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.WebFluxExceptionUtils;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.core.util.sanitize.MessageSanitizer;
import space.br1440.platform.errorhandling.core.util.sanitize.PathSanitizer;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.webflux.properties.ErrorHandlingUpstreamProperties;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static java.util.List.of;

/**
 * Стратегия классификации исключений Spring WebClient как первопричины бизнес-исключений.
 *
 * <p>Обрабатывает сценарий, когда {@link WebClientResponseException} или
 * {@link WebClientRequestException} является root cause бизнес-исключения —
 * паттерн, типичный при использовании {@code WebClient} в SERVLET-приложениях через {@code .block()}:
 * <pre>
 * OrderSubmitException (AbstractErrorResponseException)
 *   └─ cause: WebClientResponseException.ServiceUnavailable (503 от order-service)
 * </pre>
 *
 * <h2>HTTP-маппинг</h2>
 * <ul>
 *   <li>{@link WebClientRequestException} (сетевые ошибки) → {@code 503 UNAVAILABLE}</li>
 *   <li>401, 403, 404, 410, 429, 503, 504 → pass-through</li>
 *   <li>Прочие 4xx/5xx → {@code 502 BAD_GATEWAY}</li>
 * </ul>
 *
 * <h2>Безопасность (CWE-209 / CWE-400)</h2>
 * <ul>
 *   <li>Диагностические поля собираются через {@link DiagnosticMetadataBuilder}
 *       (включая вложенную и корневую причину и {@code stackHint}) — порядок ключей
 *       детерминирован ({@link java.util.LinkedHashMap} внутри builder).</li>
 *   <li>{@code upstreamUrlPath} проходит через {@link PathSanitizer}: numeric IDs
 *       (включая polyglot цифры — арабские, бенгальские и т.д.), UUIDs и hex-токены
 *       заменяются на {@code {id}}. Query string обрезается целиком.</li>
 *   <li>{@code causeMessage} проходит через {@link MessageSanitizer} до truncate
 *       (Bearer / Basic / cards / query secrets / JDBC password).</li>
 *   <li>Полный URL, тело ответа и заголовки авторизации upstream не включаются.</li>
 *   <li>{@code responseBodyLength} читается из {@code Content-Length} с fallback
 *       на материализацию массива только если размер ≤ {@code upstream.max-body-bytes}.</li>
 * </ul>
 */
public class WebFluxCauseExceptionStrategy implements RootCauseExceptionHandlingStrategy {

    private static final int MAX_CAUSE_MESSAGE_LENGTH = 300;
    private static final int CAUSE_CHAIN_DEPTH = 5;
    private static final int MAX_HEADER_VALUE_LENGTH = 128;

    private static final List<String> REQUEST_ID_HEADERS = of(
            "x-request-id", "x-correlation-id", "request-id", "x-trace-id"
    );

    private static final Set<Integer> RETRYABLE_UPSTREAM_STATUSES = Set.of(429, 502, 503, 504);

    private final StackHintExtractor stackHintExtractor;
    private final ErrorHandlingUpstreamProperties upstreamProperties;

    public WebFluxCauseExceptionStrategy(@NonNull StackHintExtractor stackHintExtractor,
                                         @NonNull ErrorHandlingUpstreamProperties upstreamProperties) {
        this.stackHintExtractor = stackHintExtractor;
        this.upstreamProperties = upstreamProperties;
    }

    @Override
    public int getOrder() {
        return RootCauseExceptionStrategyOrder.WEBFLUX;
    }

    @Override
    @NonNull
    public @NotNull LogPolicy logPolicy(@NonNull Exception exception,
                                        @NonNull ErrorEntry errorEntry) {
        return errorEntry.getHttpStatus().is5xxServerError() ? LogPolicy.ERROR : LogPolicy.WARN;
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return WebFluxExceptionUtils.isWebClientException(cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        boolean isResponseException = WebFluxExceptionUtils.isResponseException(cause);
        boolean isRequestException = WebFluxExceptionUtils.isRequestException(cause);
        int upstreamStatus = isResponseException ? WebFluxExceptionUtils.extractStatusCode(cause) : -1;

        String errorCategory = WebFluxExceptionUtils.resolveErrorCategory(isResponseException, isRequestException);
        Map<String, String> diagnosticMetadata = buildDiagnosticMetadata(
                cause, isResponseException, isRequestException, upstreamStatus, errorCategory
        );

        HttpStatus httpStatus = WebFluxExceptionUtils.mapToHttpStatus(isResponseException, isRequestException, upstreamStatus);
        String message = isRequestException
                ? "Network error while contacting upstream service"
                : "The upstream service returned an error response.";

        List<ErrorDetail> details = new ArrayList<>();

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(errorCategory)
                .domain("webflux")
                .diagnosticMetadata(diagnosticMetadata)
                .build();
        details.add(info);

        Retryability retryability = resolveRetryability(isResponseException, isRequestException, upstreamStatus);
        if (retryability == Retryability.RETRYABLE) {
            RetryInfoDetail retryInfo;
            if (isResponseException) {
                Duration retryDelay = WebFluxExceptionUtils.extractRetryAfterDelay(cause);
                retryInfo = new RetryInfoDetail(retryDelay);
            } else {
                retryInfo = new RetryInfoDetail();
            }

            details.add(retryInfo);
        }

        return RootCauseResolution.of(httpStatus, message, details, retryability);
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull Throwable cause,
                                                        boolean isResponseException,
                                                        boolean isRequestException,
                                                        int upstreamStatus,
                                                        @NonNull String errorCategory) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCause(cause, stackHintExtractor);

        builder.put("errorCategory", errorCategory);

        String causeMsg = cause.getMessage();
        if (StringUtils.hasText(causeMsg)) {
            String sanitized = MessageSanitizer.sanitizeAndTruncate(causeMsg, MAX_CAUSE_MESSAGE_LENGTH);
            builder.putIfPresent("causeMessage", sanitized);
        }

        if (upstreamStatus > 0) {
            builder.put("upstreamStatus", Integer.toString(upstreamStatus));
        }

        if (isRequestException && cause instanceof WebClientRequestException requestEx) {
            enrichWithRequestDetails(requestEx, builder);
            builder.withNestedCause(MAX_CAUSE_MESSAGE_LENGTH);
        }

        if (isResponseException && cause instanceof WebClientResponseException responseEx) {
            enrichWithResponseDetails(responseEx, builder);
        }

        return builder
                .withRootCause(CAUSE_CHAIN_DEPTH, MAX_CAUSE_MESSAGE_LENGTH)
                .withStackHint()
                .build();
    }

    private static Retryability resolveRetryability(boolean isResponseException,
                                                    boolean isRequestException,
                                                    int upstreamStatus) {
        if (isRequestException || !isResponseException) {
            return Retryability.RETRYABLE;
        }

        return RETRYABLE_UPSTREAM_STATUSES.contains(upstreamStatus)
                ? Retryability.RETRYABLE
                : Retryability.NOT_RETRYABLE;
    }

    private static void enrichWithRequestDetails(@NonNull WebClientRequestException requestEx,
                                                 @NonNull DiagnosticMetadataBuilder builder) {
        builder.put("httpMethod", requestEx.getMethod().toString());

        URI uri = requestEx.getUri();
        String host = uri.getHost();
        if (StringUtils.hasText(host)) {
            builder.put("upstreamServiceName", host);
        }

        String path = uri.getRawPath();
        if (StringUtils.hasText(path)) {
            String sanitized = PathSanitizer.sanitize(path);
            builder.putIfPresent("upstreamUrlPath", sanitized);
        }

        Throwable nested = requestEx.getCause();
        if (nested != null) {
            String nestedClass = nested.getClass().getSimpleName();
            builder.put("networkFailureType", classifyNetworkFailure(nestedClass, nested.getMessage()));
        }
    }

    @NonNull
    private static String classifyNetworkFailure(@NonNull String className, @Nullable String message) {
        if (className.contains("Timeout")) {
            return "TIMEOUT";
        }

        if ("ConnectException".equals(className) || "ConnectionRefusedException".equals(className)) {
            return "CONNECTION_REFUSED";
        }

        if (className.contains("UnknownHost") || className.contains("UnresolvedAddress")) {
            return "DNS_RESOLUTION";
        }

        if (className.contains("SSL") || className.contains("Certificate")) {
            return "TLS_HANDSHAKE";
        }

        if (message != null && message.toLowerCase(Locale.ROOT).contains("connection reset")) {
            return "CONNECTION_RESET";
        }

        return "UNKNOWN_NETWORK";
    }

    private void enrichWithResponseDetails(@NonNull WebClientResponseException responseEx,
                                           @NonNull DiagnosticMetadataBuilder builder) {
        var request = responseEx.getRequest();
        if (request != null) {
            builder.put("httpMethod", request.getMethod().toString());

            URI uri = request.getURI();
            String host = uri.getHost();
            if (StringUtils.hasText(host)) {
                builder.put("upstreamServiceName", host);
            }

            String path = uri.getRawPath();
            if (StringUtils.hasText(path)) {
                String sanitized = PathSanitizer.sanitize(path);
                builder.putIfPresent("upstreamUrlPath", sanitized);
            }
        }

        for (String header : REQUEST_ID_HEADERS) {
            String value = responseEx.getHeaders().getFirst(header);
            if (StringUtils.hasText(value)) {
                builder.putTruncatedIfPresent(
                        "upstreamRequestId",
                        value,
                        MAX_HEADER_VALUE_LENGTH);
                break;
            }
        }

        builder.putTruncatedIfPresent(
                "responseContentType",
                responseEx.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                MAX_HEADER_VALUE_LENGTH);

        long bodyLength = resolveBodyLength(responseEx);
        if (bodyLength > 0) {
            builder.put("responseBodyLength", Long.toString(bodyLength));
        }
    }

    private long resolveBodyLength(@NonNull WebClientResponseException responseEx) {
        long declared = parseContentLength(responseEx.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH));
        if (declared >= 0) {
            return declared;
        }

        long maxBodyBytes = upstreamProperties.getMaxBodyBytes().toBytes();
        byte[] body = responseEx.getResponseBodyAsByteArray();
        return (body.length <= maxBodyBytes) ? body.length : maxBodyBytes;
    }

    private static long parseContentLength(@Nullable String value) {
        if (value == null) {
            return -1L;
        }

        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }
}
