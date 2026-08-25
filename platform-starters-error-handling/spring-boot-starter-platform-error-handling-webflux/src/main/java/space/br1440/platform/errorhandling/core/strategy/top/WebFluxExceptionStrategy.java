package space.br1440.platform.errorhandling.core.strategy.top;

import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.core.util.WebFluxExceptionUtils;
import space.br1440.platform.errorhandling.core.util.http.UpstreamIntegrationDomain;
import space.br1440.platform.errorhandling.core.util.sanitize.MessageSanitizer;
import space.br1440.platform.errorhandling.core.util.sanitize.PathSanitizer;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.base.AbstractTopExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.TopExceptionStrategyOrder;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Стратегия обработки ошибок Spring WebClient (WebFlux HTTP-клиент).
 *
 * <p>Перехватывает {@link WebClientResponseException} и {@link WebClientRequestException},
 * возникающие при вызове upstream-сервисов через {@link WebClient}.
 *
 * <h2>Клиентские и диагностические метаданные</h2>
 * <p>{@code clientMetadata} собирается через {@link ClientMetadataBuilder}:
 * <ul>
 *   <li>{@code clientType} — тип клиента ({@code "WebClient"})</li>
 *   <li>{@code errorCategory} — категория ошибки ({@code network} / {@code upstream_response})</li>
 *   <li>{@code upstreamStatus} — HTTP-статус ответа upstream-сервиса (если он был получен)</li>
 * </ul>
 *
 * <p>{@code diagnosticMetadata} — внутренние данные (только логи, не в DTO):
 * <ul>
 *   <li>{@code exceptionClass} — FQCN исключения.</li>
 *   <li>{@code causeMessage} — сообщение исключения, sanitized + truncated.</li>
 *   <li>{@code upstreamHost} — hostname upstream-сервиса (для response-исключений).</li>
 *   <li>{@code upstreamUrlPath} — sanitized URL-path (динамические сегменты заменены на {@code {id}}).</li>
 *   <li>{@code rootCauseClass} — FQCN глубинной причины (если отличается, глубина до 5).</li>
 *   <li>{@code rootCauseMessage} — сообщение глубинной причины (если отличается).</li>
 *   <li>{@code stackHint} — значимые фреймы стектрейса.</li>
 * </ul>
 *
 * <h2>Безопасность (CWE-209 / CWE-400)</h2>
 * <ul>
 *   <li>URL upstream-сервиса (включая query string) не включаются в публичный ответ.</li>
 *   <li>Тело upstream-ответа эта стратегия не читает вовсе — identity прямого upstream
 *       разбирает pipeline один раз ({@code UpstreamResponseReader}), вне event-loop.</li>
 *   <li>Все сообщения проходят {@link MessageSanitizer} до truncate (Bearer / Basic / cards / query secrets).</li>
 *   <li>Path-сегменты проходят {@link PathSanitizer} с поддержкой polyglot цифр.</li>
 * </ul>
 */
public class WebFluxExceptionStrategy extends AbstractTopExceptionStrategy {

    private static final int MAX_MESSAGE_LENGTH = 200;
    private static final String CLIENT_TYPE = "WebClient";

    private static final int CAUSE_CHAIN_DEPTH = 5;
    private static final int MAX_CAUSE_MESSAGE_LENGTH = 300;

    public WebFluxExceptionStrategy(@NonNull CauseChainResolver causeChainResolver) {
        super(TopExceptionStrategyOrder.WEBFLUX, causeChainResolver);
    }

    @Override
    @NonNull
    public LogPolicy logPolicy(@NonNull Exception exception, @NonNull ErrorEntry entry) {
        return entry.getHttpStatus().is5xxServerError() ? LogPolicy.ERROR : LogPolicy.WARN;
    }

    @Override
    public boolean supports(@NonNull Exception exception) {
        return WebFluxExceptionUtils.isWebClientException(exception);
    }

    @Override
    @NonNull
    protected ErrorEntry doHandle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
        boolean isResponseException = WebFluxExceptionUtils.isResponseException(exception);
        boolean isRequestException = WebFluxExceptionUtils.isRequestException(exception);

        int upstreamStatus = isResponseException ? WebFluxExceptionUtils.extractStatusCode(exception) : -1;
        HttpStatus httpStatus = WebFluxExceptionUtils.mapToHttpStatus(isResponseException, isRequestException, upstreamStatus);

        Map<String, String> clientMetadata = buildClientMetadata(isResponseException, isRequestException, upstreamStatus);
        Map<String, String> diagnosticMetadata = buildDiagnosticMetadata(exception, isResponseException, upstreamStatus, context);

        return createWebClientEntry(
                httpStatus, isRequestException, upstreamStatus,
                clientMetadata, diagnosticMetadata, context
        );
    }

    @NonNull
    private static ErrorEntry createWebClientEntry(@NonNull HttpStatus httpStatus,
                                                   boolean isRequestException,
                                                   int upstreamStatus,
                                                   @NonNull Map<String, String> clientMetadata,
                                                   @NonNull Map<String, String> diagnosticMetadata,
                                                   @NonNull ErrorHandlingContext context) {
        if (isRequestException) {
            return createEntry(HttpStatus.SERVICE_UNAVAILABLE,
                    "Failed to establish connection with the upstream service.", clientMetadata, diagnosticMetadata, context);
        }

        return switch (httpStatus) {
            case UNAUTHORIZED -> createEntry(HttpStatus.UNAUTHORIZED,
                    "The upstream service rejected the request due to an authentication failure.", clientMetadata, diagnosticMetadata, context);

            case FORBIDDEN -> createEntry(HttpStatus.FORBIDDEN,
                    "The upstream service rejected the request due to insufficient permissions.", clientMetadata, diagnosticMetadata, context);

            case NOT_FOUND -> createEntry(HttpStatus.NOT_FOUND,
                    "The requested resource was not found in the upstream service.", clientMetadata, diagnosticMetadata, context);

            case TOO_MANY_REQUESTS -> createEntry(HttpStatus.TOO_MANY_REQUESTS,
                    "The upstream service has rejected the request due to a rate limit. Retry after a short delay.", clientMetadata, diagnosticMetadata, context);

            case SERVICE_UNAVAILABLE -> createEntry(HttpStatus.SERVICE_UNAVAILABLE,
                    "The upstream service is temporarily unavailable. Retry after a short delay.", clientMetadata, diagnosticMetadata, context);

            case GATEWAY_TIMEOUT -> createEntry(HttpStatus.GATEWAY_TIMEOUT,
                    "The upstream service did not respond within the configured timeout.", clientMetadata, diagnosticMetadata, context);

            default -> createEntry(HttpStatus.BAD_GATEWAY,
                    (upstreamStatus >= 500)
                            ? "The upstream service returned an unexpected error."
                            : "The upstream service rejected the request.",
                    clientMetadata, diagnosticMetadata, context);
        };
    }

    @NonNull
    private static Map<String, String> buildClientMetadata(boolean isResponseException,
                                                           boolean isRequestException,
                                                           int upstreamStatus) {
        ClientMetadataBuilder clientBuilder = ClientMetadataBuilder.builder()
                .put("clientType", CLIENT_TYPE)
                .put("errorCategory", WebFluxExceptionUtils.resolveErrorCategory(isResponseException, isRequestException));

        if (upstreamStatus > 0) {
            clientBuilder.put("upstreamStatus", upstreamStatus);
        }

        return clientBuilder.build();
    }

    @NonNull
    private static Map<String, String> buildDiagnosticMetadata(@NonNull Exception exception,
                                                               boolean isResponseException,
                                                               int upstreamStatus,
                                                               @NonNull ErrorHandlingContext context) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCause(exception, context.getStackHintExtractor());

        String causeMessage = exception.getMessage();
        if (StringUtils.hasText(causeMessage)) {
            String sanitized = MessageSanitizer.sanitizeAndTruncate(causeMessage, MAX_CAUSE_MESSAGE_LENGTH);
            builder.putIfPresent("causeMessage", sanitized);
        }

        if (upstreamStatus > 0) {
            builder.put("upstreamStatus", Integer.toString(upstreamStatus));
        }

        if (isResponseException && exception instanceof WebClientResponseException response) {
            HttpRequest request = response.getRequest();
            if (request != null) {
                URI uri = request.getURI();
                builder.putIfPresent("upstreamHost", uri.getHost());

                String path = uri.getRawPath();
                if (StringUtils.hasText(path)) {
                    String sanitizedPath = PathSanitizer.sanitize(path);
                    builder.putIfPresent("upstreamUrlPath", sanitizedPath);
                }
            }
        }

        return builder
                .withRootCause(CAUSE_CHAIN_DEPTH, MAX_MESSAGE_LENGTH)
                .withStackHint()
                .build();
    }

    @NonNull
    private static ErrorEntry createEntry(HttpStatus httpStatus,
                                          @NonNull String message,
                                          @NonNull Map<String, String> clientMetadata,
                                          @NonNull Map<String, String> diagnosticMetadata,
                                          @NonNull ErrorHandlingContext context) {
        ErrorInfoDetail.ErrorInfoDetailBuilder infoBuilder = ErrorInfoDetail.builder()
                .reason(clientMetadata.getOrDefault("errorCategory", "WEB_CLIENT"))
                .domain(UpstreamIntegrationDomain.HTTP_INTEGRATION);

        if (!clientMetadata.isEmpty()) {
            infoBuilder.clientMetadata(clientMetadata);
        }

        if (!diagnosticMetadata.isEmpty()) {
            infoBuilder.diagnosticMetadata(diagnosticMetadata);
        }

        ErrorInfoDetail info = infoBuilder.build();

        return ErrorEntry.builder()
                .httpStatus(httpStatus)
                .errorCode(WebFluxExceptionUtils.determineErrorCode(httpStatus))
                .message(message)
                .requestURI(context.getRequestPath())
                .details(List.of(info))
                .build();
    }
}
