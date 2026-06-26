package space.br1440.platform.errorhandling.core.strategy.cause.network;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import space.br1440.platform.errorhandling.core.util.CauseMetadataEnricher;
import space.br1440.platform.errorhandling.core.util.ClassUtils;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.ReflectionUtils;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.core.util.http.RetryAfterHeaderParser;
import space.br1440.platform.errorhandling.core.util.sanitize.LogSanitizer;
import space.br1440.platform.errorhandling.core.util.sanitize.PathSanitizer;
import space.br1440.platform.errorhandling.extension.ErrorClassification;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;

import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Стратегия обработки исключений HTTP-интеграций с upstream-сервисами для синхронных
 * ({@code RestTemplate}, {@code RestClient}) и реактивных ({@code WebClient}) клиентов
 * Spring Framework.
 *
 * <h2>Поддерживаемые HTTP-клиенты</h2>
 * <ul>
 *   <li><b>RestTemplate</b> — {@link HttpStatusCodeException} (4xx/5xx),
 *       {@link ResourceAccessException} (сетевые ошибки, обёртка над {@code IOException}).</li>
 *   <li><b>RestClient</b> (Spring 6.1+) — {@link RestClientResponseException} (4xx/5xx,
 *       бросается при отсутствии кастомного {@code .onStatus()} обработчика),
 *       {@link ResourceAccessException} (сетевые ошибки — общий класс с RestTemplate).
 *       <p><b>Важно:</b> класс {@code RestClientRequestException} в Spring Framework
 *       <i>отсутствует</i> — иерархия {@code RestClient} переиспользует
 *       {@code ResourceAccessException} для сетевых ошибок.</li>
 *   <li><b>WebClient</b> (Spring WebFlux) — {@code WebClientResponseException} (4xx/5xx,
 *       имеет {@code getRequest()} с 5.1.4) и {@code WebClientRequestException}
 *       (сетевые ошибки, доступны {@code getMethod()}/{@code getUri()}/{@code getHeaders()}
 *       напрямую). Загружаются через reflection как optional.</li>
 *   <li><b>Прямые сетевые исключения</b> — {@link SocketTimeoutException},
 *       {@link TimeoutException}, {@link ConnectException}, {@link UnknownHostException}.</li>
 * </ul>
 *
 * <h3>Иерархия типов</h3>
 * <pre>
 *   RestClientResponseException          ← RestClient: 4xx/5xx (общая база)
 *   ├── HttpStatusCodeException          ← RestTemplate: 4xx/5xx (подкласс)
 *   │   ├── HttpClientErrorException     (4xx)
 *   │   └── HttpServerErrorException     (5xx)
 *   └── UnknownHttpStatusCodeException   ← нестандартные статусы
 * </pre>
 * <p>В {@link #classifyNetworkCause(Throwable)} порядок проверок: сначала {@code switch}
 * по конкретным сетевым типам; затем — generic instance-проверки для
 * {@code RestClientResponseException}/WebClient через reflection.
 *
 * <h3>Диагностические метаданные</h3>
 * <p>Поля {@code diagnosticMetadata} (внутренние логи/мониторинг, <b>не</b> клиентский ответ):</p>
 * <ul>
 *   <li>{@code exceptionClass} — FQCN исключения.</li>
 *   <li>{@code causeMessage} — сообщение исключения, обрезанное до {@value #MAX_CAUSE_MESSAGE_LENGTH}
 *       символов. Для {@link HttpStatusCodeException}/{@link RestClientResponseException}
 *       обрезается до префикса перед телом ответа (CWE-209). Не включается для wrapper-типов
 *       ({@code ResourceAccessException}, {@code WebClientRequestException}).</li>
 *   <li>{@code nestedCauseClass} / {@code nestedCauseMessage} — для wrapper-типов выше.</li>
 *   <li>{@code networkErrorType} — классификация: {@code SOCKET_TIMEOUT}, {@code TIMEOUT},
 *       {@code CONNECT_REFUSED}, {@code DNS_FAILURE}, {@code RATE_LIMITED} (HTTP 429),
 *       {@code OVERLOADED} (502/503/504), {@code CLIENT_ERROR} (прочие 4xx),
 *       {@code SERVER_ERROR} (прочие 5xx), {@code NETWORK_ERROR}.</li>
 *   <li>{@code restClientType} — {@code REST_TEMPLATE}, {@code REST_CLIENT},
 *       {@code WEBCLIENT}, {@code UNKNOWN}.</li>
 *   <li>{@code httpMethod} — HTTP-метод запроса.</li>
 *   <li>{@code upstreamStatus} — HTTP-статус от upstream (для всех {@code *_ERROR}/{@code RATE_LIMITED}/{@code OVERLOADED}).</li>
 *   <li>{@code upstreamErrorCategory} — {@code CLIENT_ERROR} / {@code SERVER_ERROR}
 *       (broad-семантика, для агрегации в SLO-дашбордах).</li>
 *   <li>{@code upstreamRequestId} — request-id из upstream response headers по whitelist.</li>
 *   <li>{@code responseBodyLength} / {@code responseContentType} — длина и Content-Type
 *       тела ответа (само тело <b>не</b> включается — CWE-209).</li>
 *   <li>{@code retryAfterMs} / {@code retryAfterSource} — задержка из {@code Retry-After}
 *       (RFC 9110 §10.2.3): {@code header}, либо отсутствует.</li>
 *   <li>{@code upstreamServiceName} / {@code upstreamUrlPath} / {@code upstreamPort} —
 *       координаты upstream (только когда доступны через {@code getRequest()} —
 *       {@code WebClientResponseException} и {@code WebClientRequestException}).</li>
 *   <li>{@code rootCauseClass} / {@code rootCauseMessage} — глубинная причина
 *       (если отличается, глубина = {@link CauseMetadataEnricher#DIAGNOSTIC_CAUSE_CHAIN_DEPTH}).</li>
 *   <li>{@code stackHint} — значимые фреймы стектрейса.</li>
 * </ul>
 *
 * <h3>Безопасность (CWE-209, CWE-117)</h3>
 * <ul>
 *   <li>Reason phrase от upstream-сервиса <b>не</b> включается в ответ клиенту —
 *       используется стандартный reason phrase из {@link HttpStatus}.</li>
 *   <li>Тело ответа upstream не сериализуется в metadata — может содержать PII/credentials.
 *       Spring форматирует тело в {@code .getMessage()} вида {@code "504 Gateway Timeout: <body>"};
 *       мы обрезаем строку до {@code statusCode + reasonPhrase}, тело отбрасывается.</li>
 *   <li>{@code upstreamUrlPath} прогоняется через {@link PathSanitizer}: numeric/UUID/hex-токены
 *       заменяются на {@code {id}} — защита от утечки JWT/session-id в path-сегментах
 *       и от взрыва кардинальности метрик.</li>
 *   <li>{@code upstreamRequestId} извлекается только по явному whitelist заголовков,
 *       значение санитизируется через {@link LogSanitizer} (CWE-117) и обрезается
 *       до {@value #MAX_REQUEST_ID_LENGTH} символов.</li>
 * </ul>
 *
 * <h3>Соотношение со смежными стратегиями</h3>
 * <ul>
 *   <li>{@code TimeoutExceptionStrategy} (ORDER=335) перехватывает {@link SocketTimeoutException} и
 *       {@link TimeoutException} раньше — здесь они обрабатываются как defence-in-depth fallback.</li>
 *   <li>{@code DnsResolutionExceptionStrategy} (ORDER=340) перехватывает {@link UnknownHostException} раньше.</li>
 *   <li>{@code FeignRootCauseExceptionStrategy} (ORDER=605) обрабатывает Feign отдельно
 *       (свой маппинг 4xx → 500 для bug-сценариев, поддержка {@code RetryableException.retryAfter()}).</li>
 * </ul>
 */
public class HttpIntegrationExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    /** Максимальная длина {@code causeMessage} в diagnostic-metadata. */
    private static final int MAX_CAUSE_MESSAGE_LENGTH = 300;

    /** Максимальная длина значения {@code upstreamRequestId} (sanitized). */
    private static final int MAX_REQUEST_ID_LENGTH = 128;

    /**
     * Whitelist заголовков для извлечения correlation/trace ID. Включает {@code traceparent}
     * (W3C Trace Context) — индустриальный стандарт распределённой трассировки.
     * Порядок отражает приоритет извлечения (первый matching выигрывает).
     */
    private static final List<String> REQUEST_ID_HEADERS = List.of(
            "x-request-id",
            "x-correlation-id",
            "request-id",
            "x-trace-id",
            "traceparent"
    );

    private static final ClassLoader LOADER = HttpIntegrationExceptionStrategy.class.getClassLoader();

    @Nullable
    private static final Class<?> WEBCLIENT_RESPONSE_EXCEPTION = ReflectionUtils.loadOptionalClass(
            "org.springframework.web.reactive.function.client.WebClientResponseException", LOADER);

    @Nullable
    private static final Class<?> WEBCLIENT_REQUEST_EXCEPTION = ReflectionUtils.loadOptionalClass(
            "org.springframework.web.reactive.function.client.WebClientRequestException", LOADER);

    /**
     * Reactor Netty: {@code PrematureCloseException} оборачивается в {@code WebClientRequestException},
     * но также может всплыть напрямую в нестандартных pipeline-сценариях.
     */
    @Nullable
    private static final Class<?> REACTOR_PREMATURE_CLOSE = ReflectionUtils.loadOptionalClass(
            "reactor.netty.http.client.PrematureCloseException", LOADER);

    public HttpIntegrationExceptionStrategy(StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.HTTP_INTEGRATION, stackHintExtractor);
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        if (!(cause instanceof Exception)) {
            return false;
        }

        // Explicit loop вместо stream — горячий путь error-handling pipeline.
        if (cause instanceof TimeoutException
                || cause instanceof SocketTimeoutException
                || cause instanceof ConnectException
                || cause instanceof UnknownHostException
                || cause instanceof ResourceAccessException
                || cause instanceof RestClientResponseException) {
            return true;
        }

        return ClassUtils.isInstanceOf(WEBCLIENT_RESPONSE_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(WEBCLIENT_REQUEST_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(REACTOR_PREMATURE_CLOSE, cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        NetworkErrorType type = classifyNetworkCause(cause);
        int upstreamStatus = isUpstreamHttpError(type) ? extractStatusCode(cause) : -1;
        Duration retryAfter = extractRetryAfter(cause);

        // Расчёт retryability — единственный источник истины: enum-семантика
        // плюс точечная коррекция для UPSTREAM_HTTP_ERROR-семейства по статусу
        // и наличию Retry-After.
        Retryability retryability = computeRetryability(type, upstreamStatus, retryAfter);

        List<ErrorDetail> details = new ArrayList<>(2);
        if (retryability == Retryability.RETRYABLE) {
            details.add(retryAfter != null ? new RetryInfoDetail(retryAfter) : new RetryInfoDetail());
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(type.name())
                .domain("http-integration")
                .diagnosticMetadata(buildDiagnosticMetadata(type, cause, upstreamStatus, retryAfter))
                .build();
        details.add(info);

        String message = buildCauseMessage(type, upstreamStatus);
        return RootCauseResolution.of(type.httpStatus(), message, details, retryability);
    }

    @NonNull
    private static String buildCauseMessage(@NonNull NetworkErrorType type, int upstreamStatus) {
        if (!isUpstreamHttpError(type)) {
            return type.message();
        }

        if (upstreamStatus > 0) {
            return formatUpstreamMessage(upstreamStatus);
        }

        return type.message();
    }

    @NonNull
    private static String formatUpstreamMessage(int statusValue) {
        HttpStatus resolved = HttpStatus.resolve(statusValue);
        // Reason phrase из стандарта HTTP — безопасно (CWE-209: не используем тело ответа upstream).
        String safeReason = (resolved != null) ? resolved.getReasonPhrase() : "Unknown Status";
        return String.format("Upstream service returned %d %s", statusValue, safeReason);
    }

    @NonNull
    private static NetworkErrorType classifyNetworkCause(@NonNull Throwable cause) {
        // 1) Прямые сетевые исключения — defence-in-depth (обычно перехватываются
        //    TimeoutExceptionStrategy/DnsResolutionExceptionStrategy раньше по ORDER).
        if (cause instanceof SocketTimeoutException) {
            return NetworkErrorType.SOCKET_TIMEOUT;
        }
        if (cause instanceof TimeoutException) {
            return NetworkErrorType.TIMEOUT;
        }
        if (cause instanceof ConnectException) {
            return NetworkErrorType.CONNECT_REFUSED;
        }
        if (cause instanceof UnknownHostException) {
            return NetworkErrorType.DNS_FAILURE;
        }

        // 2) Reactor Netty premature close — server закрыл TCP-соединение до отправки full response.
        if (ClassUtils.isInstanceOf(REACTOR_PREMATURE_CLOSE, cause)) {
            return NetworkErrorType.NETWORK_ERROR;
        }

        // 3) HTTP-ответы 4xx/5xx — гранулярная классификация.
        //    HttpStatusCodeException ⊂ RestClientResponseException — общая база покрывает оба.
        if (cause instanceof RestClientResponseException restEx) {
            return classifyByStatus(restEx.getStatusCode().value());
        }
        if (ClassUtils.isInstanceOf(WEBCLIENT_RESPONSE_EXCEPTION, cause)) {
            int status = ReflectionUtils.extractHttpStatusCode(cause);
            return status > 0 ? classifyByStatus(status) : NetworkErrorType.SERVER_ERROR;
        }

        // 4) Сетевые wrappers — рекурсивно по cause-chain (с защитой от циклов).
        if (cause instanceof ResourceAccessException
                || ClassUtils.isInstanceOf(WEBCLIENT_REQUEST_EXCEPTION, cause)) {
            Throwable nested = cause.getCause();
            if (nested != null && nested != cause) {
                return classifyNetworkCause(nested);
            }

            return NetworkErrorType.NETWORK_ERROR;
        }

        return NetworkErrorType.NETWORK_ERROR;
    }

    /**
     * Гранулярная классификация по HTTP-статусу: 429 → {@code RATE_LIMITED},
     * 502/503/504 → {@code OVERLOADED} (transient infra), прочие 4xx → {@code CLIENT_ERROR},
     * прочие 5xx → {@code SERVER_ERROR}.
     */
    @NonNull
    private static NetworkErrorType classifyByStatus(int status) {
        return switch (status) {
            case 429 -> NetworkErrorType.RATE_LIMITED;
            case 502, 503, 504 -> NetworkErrorType.OVERLOADED;
            default -> {
                if (status >= 500) {
                    yield NetworkErrorType.SERVER_ERROR;
                }
                if (status >= 400) {
                    yield NetworkErrorType.CLIENT_ERROR;
                }

                // 1xx/2xx/3xx сюда не должны попадать (это не error-кейс), но для
                // корректности классификации трактуем как SERVER_ERROR.
                yield NetworkErrorType.SERVER_ERROR;
            }
        };
    }

    private static boolean isUpstreamHttpError(@NonNull NetworkErrorType type) {
        return type == NetworkErrorType.RATE_LIMITED
                || type == NetworkErrorType.OVERLOADED
                || type == NetworkErrorType.CLIENT_ERROR
                || type == NetworkErrorType.SERVER_ERROR;
    }

    /**
     * Извлекает HTTP status code: для {@link RestClientResponseException} — напрямую,
     * иначе — через платформенную утилиту {@link ReflectionUtils#extractHttpStatusCode}
     * (поддерживает WebClient/RestClient/Feign и любые типы с методом {@code getStatusCode()}).
     */
    private static int extractStatusCode(@NonNull Throwable cause) {
        if (cause instanceof RestClientResponseException restEx) {
            return restEx.getStatusCode().value();
        }

        int status = ReflectionUtils.extractHttpStatusCode(cause);
        return status > 0 ? status : -1;
    }

    /**
     * Расчёт retryability:
     * <ul>
     *   <li>Прямые сетевые ошибки и {@code OVERLOADED} (5xx infra) — {@code RETRYABLE}
     *       по семантике (определяется в enum {@link NetworkErrorType}).</li>
     *   <li>{@code RATE_LIMITED} (429) — {@code RETRYABLE} строго при наличии {@code Retry-After}:
     *       без указания задержки повтор может усугубить нагрузку (rate-limit storm).</li>
     *   <li>{@code CLIENT_ERROR} (4xx) — {@code NOT_RETRYABLE} (наш баг — повтор не поможет).</li>
     *   <li>{@code SERVER_ERROR} (прочие 5xx, не из {@code OVERLOADED}) — {@code NOT_RETRYABLE}
     *       (потенциально багованный server-side ответ, повтор без stop-criterion опасен).</li>
     * </ul>
     */
    @NonNull
    private static Retryability computeRetryability(@NonNull NetworkErrorType type,
                                                    int upstreamStatus,
                                                    @Nullable Duration retryAfter) {
        if (type == NetworkErrorType.RATE_LIMITED) {
            return retryAfter != null ? Retryability.RETRYABLE : Retryability.NOT_RETRYABLE;
        }

        return type.retryability();
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull NetworkErrorType type,
                                                        @NonNull Throwable cause,
                                                        int upstreamStatus,
                                                        @Nullable Duration retryAfter) {
        DiagnosticMetadataBuilder builder = diagnosticMetadataFor(cause);

        // CWE-209: для HTTP-status исключений Spring форматирует тело ответа в .getMessage()
        // вида "504 Gateway Timeout: \"<body>\"". Обрезаем до префикса со статусом, тело отбрасываем.
        if (shouldIncludeCauseMessage(cause)) {
            builder.putTruncatedIfPresent("causeMessage",
                    sanitizeCauseMessage(cause), MAX_CAUSE_MESSAGE_LENGTH);
        } else {
            // Wrapper-типы: их .getMessage() обычно содержит URL/body — раскрываем nested.
            Throwable nested = cause.getCause();
            if (nested != null && nested != cause) {
                builder.put("nestedCauseClass", nested.getClass().getName());
                builder.putTruncatedIfPresent("nestedCauseMessage",
                        nested.getMessage(), MAX_CAUSE_MESSAGE_LENGTH);
            }
        }

        builder.put("networkErrorType", type.name());
        builder.put("restClientType", detectRestClientType(cause));

        enrichWithHttpMethod(cause, builder);

        if (isUpstreamHttpError(type)) {
            enrichUpstreamHttpMetadata(cause, upstreamStatus, builder);
        }

        if (retryAfter != null) {
            builder.put("retryAfterMs", Long.toString(retryAfter.toMillis()));
            builder.put("retryAfterSource", "header");
        }

        enrichUpstreamCoordinates(cause, builder);
        builder.withRootCause();

        return builder.withStackHint().build();
    }

    /**
     * CWE-209: {@link RestClientResponseException} форматирует {@code .getMessage()} как
     * {@code "<status> <statusText>: \"<body>\""} начиная со Spring 5.2. Тело ответа
     * может содержать PII/credentials. Возвращаем только префикс до тела.
     *
     * <p>Для прочих типов — message без модификации (truncate сделает builder).
     */
    @Nullable
    private static String sanitizeCauseMessage(@NonNull Throwable cause) {
        String message = cause.getMessage();
        if (!StringUtils.hasText(message)) {
            return null;
        }

        if (cause instanceof RestClientResponseException
                || ClassUtils.isInstanceOf(WEBCLIENT_RESPONSE_EXCEPTION, cause)) {
            // Spring-форматы:
            //   RestClientResponseException: "<status> <statusText>: \"<body>\""
            //   WebClientResponseException:  "<status> <statusText> from <method> <url>"
            // Защищаемся обрезанием по первому ':' (после статус-префикса) или ' from '.
            int sep = message.indexOf(':');
            int fromIdx = message.indexOf(" from ");
            int cut = -1;
            if (sep > 0 && (fromIdx < 0 || sep < fromIdx)) {
                cut = sep;
            } else if (fromIdx > 0) {
                cut = fromIdx;
            }

            if (cut > 0) {
                return message.substring(0, cut);
            }
        }

        return message;
    }

    private static boolean shouldIncludeCauseMessage(@NonNull Throwable cause) {
        // Wrapper-типы: их message содержит исходный URL запроса — не раскрываем.
        return !(cause instanceof ResourceAccessException)
                && !ClassUtils.isInstanceOf(WEBCLIENT_REQUEST_EXCEPTION, cause);
    }

    /**
     * Извлекает upstream coordinates (host/path/port) — доступно только для типов
     * с методом {@code getRequest()}/{@code getUri()}: {@code WebClientResponseException}
     * (since 5.1.4) и {@code WebClientRequestException}. Для {@link HttpStatusCodeException}
     * и {@link RestClientResponseException} request-данные недоступны (Spring issue #33814).
     */
    private static void enrichUpstreamCoordinates(@NonNull Throwable cause,
                                                  @NonNull DiagnosticMetadataBuilder builder) {
        URI uri = extractRequestUri(cause);
        if (uri == null) {
            return;
        }

        String host = uri.getHost();
        if (StringUtils.hasText(host)) {
            builder.put("upstreamServiceName", LogSanitizer.sanitize(host, MAX_REQUEST_ID_LENGTH));
        }

        String path = uri.getRawPath();
        if (StringUtils.hasText(path)) {
            // PathSanitizer: заменяет UUID/numeric/hex-сегменты на {id}, обрезает query.
            builder.put("upstreamUrlPath", PathSanitizer.sanitize(path));
        }

        int port = uri.getPort();
        if (port > 0 && !isStandardPort(uri.getScheme(), port)) {
            builder.put("upstreamPort", Integer.toString(port));
        }
    }

    @Nullable
    private static URI extractRequestUri(@NonNull Throwable cause) {
        // WebClientRequestException: getUri() напрямую (нет getRequest()).
        if (ClassUtils.isInstanceOf(WEBCLIENT_REQUEST_EXCEPTION, cause)) {
            return invokeUri(cause);
        }

        // WebClientResponseException: getRequest() → HttpRequest → getURI().
        URI[] holder = {null};
        ReflectionUtils.invokeMethod(cause, "getRequest", request -> {
            if (request != null) {
                holder[0] = invokeUri(request);
            }
        });
        return holder[0];
    }

    @Nullable
    private static URI invokeUri(@NonNull Object target) {
        URI[] holder = {null};
        // Сначала getURI() (HttpRequest), затем fallback на getUri() (WebClientRequestException).
        ReflectionUtils.invokeMethod(target, "getURI", value -> {
            if (value instanceof URI uri) {
                holder[0] = uri;
            }
        });
        if (holder[0] == null) {
            ReflectionUtils.invokeMethod(target, "getUri", value -> {
                if (value instanceof URI uri) {
                    holder[0] = uri;
                }
            });
        }

        return holder[0];
    }

    private static boolean isStandardPort(@Nullable String scheme, int port) {
        return ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
    }

    @NonNull
    private static String detectRestClientType(@NonNull Throwable cause) {
        if (ClassUtils.isInstanceOf(WEBCLIENT_RESPONSE_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(WEBCLIENT_REQUEST_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(REACTOR_PREMATURE_CLOSE, cause)) {
            return "WEBCLIENT";
        }

        // RestTemplate и RestClient (Spring 6.1+) бросают одни и те же типы:
        // HttpStatusCodeException — родитель для RestTemplate; RestClientResponseException —
        // общая база. Различить их по типу исключения невозможно — оставляем generic-ярлык.
        if (cause instanceof HttpStatusCodeException || cause instanceof ResourceAccessException) {
            return "REST_TEMPLATE";
        }
        if (cause instanceof RestClientResponseException) {
            return "REST_CLIENT";
        }

        return "UNKNOWN";
    }

    private static void enrichUpstreamHttpMetadata(@NonNull Throwable cause,
                                                   int upstreamStatus,
                                                   @NonNull DiagnosticMetadataBuilder builder) {
        if (upstreamStatus > 0) {
            builder.put("upstreamStatus", Integer.toString(upstreamStatus));
            builder.put("upstreamErrorCategory", resolveUpstreamCategory(upstreamStatus));
        }

        // Тело и заголовки доступны через getResponseBodyAsByteArray()/getResponseHeaders()
        // у RestClientResponseException и WebClientResponseException (общий контракт по имени).
        ReflectionUtils.invokeMethod(cause, "getResponseBodyAsByteArray", body -> {
            if (body instanceof byte[] bytes && bytes.length > 0) {
                builder.put("responseBodyLength", Integer.toString(bytes.length));
            }
        });

        if (cause instanceof RestClientResponseException restEx) {
            HttpHeaders headers = restEx.getResponseHeaders();
            if (headers != null) {
                if (headers.getContentType() != null) {
                    builder.put("responseContentType", headers.getContentType().toString());
                }
                extractUpstreamRequestId(headers, builder);
            }
            return;
        }

        // WebClientResponseException и аналоги — через reflection.
        ReflectionUtils.invokeMethod(cause, "getHeaders", headers -> {
            if (headers instanceof HttpHeaders httpHeaders) {
                if (httpHeaders.getContentType() != null) {
                    builder.put("responseContentType", httpHeaders.getContentType().toString());
                }
                extractUpstreamRequestId(httpHeaders, builder);
            }
        });
    }

    private static void extractUpstreamRequestId(@NonNull HttpHeaders headers,
                                                 @NonNull DiagnosticMetadataBuilder builder) {
        for (String headerName : REQUEST_ID_HEADERS) {
            String value = headers.getFirst(headerName);
            if (StringUtils.hasText(value)) {
                // CWE-117: значение из upstream — sanitize обязательно.
                builder.put("upstreamRequestId",
                        LogSanitizer.sanitize(value, MAX_REQUEST_ID_LENGTH));
                return;
            }
        }
    }

    /**
     * Извлекает {@code Retry-After} из response headers (RFC 9110 §10.2.3).
     * Поддерживаются оба формата: {@code delta-seconds} и {@code HTTP-date}.
     */
    @Nullable
    private static Duration extractRetryAfter(@NonNull Throwable cause) {
        HttpHeaders headers = extractResponseHeaders(cause);
        if (headers == null) {
            return null;
        }

        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (!StringUtils.hasText(value)) {
            return null;
        }

        return RetryAfterHeaderParser.parse(value);
    }

    @Nullable
    private static HttpHeaders extractResponseHeaders(@NonNull Throwable cause) {
        if (cause instanceof RestClientResponseException restEx) {
            return restEx.getResponseHeaders();
        }

        HttpHeaders[] holder = {null};
        // WebClientResponseException: getHeaders() возвращает HttpHeaders напрямую.
        ReflectionUtils.invokeMethod(cause, "getHeaders", headers -> {
            if (headers instanceof HttpHeaders httpHeaders) {
                holder[0] = httpHeaders;
            }
        });
        return holder[0];
    }

    /**
     * Извлекает HTTP-метод. Доступно для:
     * <ul>
     *   <li>{@code WebClientResponseException} (через {@code getRequest().getMethod()}, since 5.1.4)</li>
     *   <li>{@code WebClientRequestException} (напрямую {@code getMethod()})</li>
     * </ul>
     */
    private static void enrichWithHttpMethod(@NonNull Throwable cause,
                                             @NonNull DiagnosticMetadataBuilder builder) {
        // WebClientRequestException: getMethod() напрямую (нет getRequest()).
        if (ClassUtils.isInstanceOf(WEBCLIENT_REQUEST_EXCEPTION, cause)) {
            invokeMethodName(cause, builder);
            return;
        }

        // WebClientResponseException: getRequest() → HttpRequest → getMethod().
        ReflectionUtils.invokeMethod(cause, "getRequest", request -> {
            if (request != null) {
                invokeMethodName(request, builder);
            }
        });
    }

    private static void invokeMethodName(@NonNull Object target,
                                         @NonNull DiagnosticMetadataBuilder builder) {
        ReflectionUtils.invokeMethod(target, "getMethod", method -> {
            if (method instanceof Enum<?> e) {
                builder.put("httpMethod", e.name());
            } else if (method != null) {
                String methodStr = method.toString();
                if (StringUtils.hasText(methodStr)) {
                    builder.put("httpMethod", methodStr);
                }
            }
        });
    }

    @NonNull
    private static String resolveUpstreamCategory(int statusValue) {
        if (statusValue >= 500) {
            return "SERVER_ERROR";
        }
        if (statusValue >= 400) {
            return "CLIENT_ERROR";
        }

        return "UPSTREAM_ERROR";
    }

    /**
     * Гранулярная классификация HTTP-ошибок upstream-сервиса.
     * <p>HTTP-маппинг outbound (наш ответ клиенту) сохраняет принцип "не утаиваем
     * семантику": 5xx-ошибки upstream → 502/504 (gateway-семейство), 429 → 502
     * (не пробрасываем 429 наружу — клиент не должен предполагать, что rate-limit
     * у upstream означает rate-limit у нас), 4xx-ошибки upstream → 502 (наш баг
     * сформировал плохой запрос либо upstream вернул ошибку клиенту "не нам").
     */
    private enum NetworkErrorType implements ErrorClassification {

        SOCKET_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, Retryability.RETRYABLE,
                "A socket read or connection timed out. The upstream service did not respond in time."),

        TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, Retryability.RETRYABLE,
                "Upstream service did not respond within the configured timeout."),

        CONNECT_REFUSED(HttpStatus.SERVICE_UNAVAILABLE, Retryability.RETRYABLE,
                "Unable to connect to upstream service."),

        DNS_FAILURE(HttpStatus.SERVICE_UNAVAILABLE, Retryability.RETRYABLE,
                "Unable to resolve upstream service host."),

        /** HTTP 429 Too Many Requests: семантика повтора зависит от наличия {@code Retry-After}. */
        RATE_LIMITED(HttpStatus.BAD_GATEWAY, Retryability.UNSPECIFIED,
                "Upstream service rate limit was exceeded."),

        /** HTTP 502/503/504: transient infrastructure/availability проблемы — повтор имеет смысл. */
        OVERLOADED(HttpStatus.BAD_GATEWAY, Retryability.RETRYABLE,
                "Upstream service is temporarily unavailable or overloaded."),

        /** Прочие 4xx: неверный запрос со стороны нашего сервиса — повтор бесполезен. */
        CLIENT_ERROR(HttpStatus.BAD_GATEWAY, Retryability.NOT_RETRYABLE,
                "Upstream service returned a client error response."),

        /** Прочие 5xx: server-side ошибка upstream — повтор без stop-criterion опасен. */
        SERVER_ERROR(HttpStatus.BAD_GATEWAY, Retryability.NOT_RETRYABLE,
                "Upstream service returned a server error response."),

        NETWORK_ERROR(HttpStatus.SERVICE_UNAVAILABLE, Retryability.RETRYABLE,
                "An unexpected network error occurred during upstream service communication.");

        private final HttpStatus httpStatus;
        private final Retryability retryability;
        private final String message;

        NetworkErrorType(@NonNull HttpStatus httpStatus,
                         @NonNull Retryability retryability,
                         @NonNull String message) {
            this.httpStatus = httpStatus;
            this.retryability = retryability;
            this.message = message;
        }

        @Override
        @NonNull
        public HttpStatus httpStatus() {
            return httpStatus;
        }

        @Override
        @NonNull
        public Retryability retryability() {
            return retryability;
        }

        @Override
        @NonNull
        public String message() {
            return message;
        }
    }
}
