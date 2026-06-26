package space.br1440.platform.errorhandling.core.util.http;

import lombok.experimental.UtilityClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.ClassUtils;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.ReflectionUtils;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.core.util.sanitize.LogSanitizer;
import space.br1440.platform.errorhandling.core.util.sanitize.PathSanitizer;

import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Утилиты для работы с исключениями OpenFeign.
 *
 * <p>Все Feign-классы загружаются через reflection — утилита компилируется
 * без наличия {@code io.github.openfeign:feign-core} на classpath.
 *
 * <p><b>Поддерживаемая версия Feign:</b> 10+
 * (API: {@code FeignException#status()}, {@code request()}, {@code responseHeaders()}, {@code RetryableException#retryAfter()}).
 *
 * <h2>Безопасность (CWE-209, CWE-117)</h2>
 * <ul>
 *   <li>URL query params не включаются — могут содержать токены/API-ключи.</li>
 *   <li>{@code upstreamPath} прогоняется через {@link PathSanitizer} (numeric/hex/UUID-сегменты → {@code {id}}),
 *       чтобы token-based роутинг не утекал в логи и не раздувал кардинальность агрегаций.</li>
 *   <li>Response/request body не включается — может содержать PII/credentials.</li>
 *   <li>Заголовки {@code Authorization}, {@code Cookie} не включаются.</li>
 *   <li>{@code causeMessage} из {@code FeignException.getMessage()} не включается — содержит полный URL и response body.</li>
 *   <li>Все извлекаемые из upstream-сообщений и заголовков значения проходят через {@link LogSanitizer}
 *       (CRLF, NUL, TAB, bidi-override) — CWE-117 Log Injection.</li>
 * </ul>
 */
@UtilityClass
public final class FeignExceptionUtils {

    private static final Logger log = LoggerFactory.getLogger(FeignExceptionUtils.class);

    private static final int MAX_HEADER_LENGTH = 50;
    private static final int MAX_METHOD_KEY_LENGTH = 100;

    /** Минимальный валидный HTTP-статус (RFC 9110): 100. Значения {@code < MIN} означают «нет статуса». */
    private static final int MIN_VALID_HTTP_STATUS = 100;
    private static final int MAX_VALID_HTTP_STATUS = 599;

    /** Источник {@code Retry-After}: HTTP-заголовок ответа upstream. */
    public static final String RETRY_AFTER_SOURCE_HEADER = "header";

    /** Источник {@code Retry-After}: {@code RetryableException.retryAfter()}. */
    public static final String RETRY_AFTER_SOURCE_EXCEPTION = "exception";

    /**
     * Паттерн Feign methodKey: {@code ClassName#method(params)}, в квадратных скобках.
     * Встречается в середине сообщения вида
     * {@code [503] during [GET] to [url] [Client#method(Args)]: [body]}.
     */
    static final Pattern FEIGN_METHOD_KEY = Pattern.compile("\\[([\\w$.]+#\\w+\\([^)]*\\))]");

    private static final ClassLoader LOADER = FeignExceptionUtils.class.getClassLoader();

    @Nullable
    private static final Class<?> FEIGN_EXCEPTION_CLASS = ReflectionUtils.loadOptionalClass("feign.FeignException", LOADER);

    @Nullable
    private static final Class<?> RETRYABLE_EXCEPTION_CLASS = ReflectionUtils.loadOptionalClass("feign.RetryableException", LOADER);

    /** Имена ключей diagnostic-metadata. Вынесены в константы для рефакторинг-safety и navigation. */
    public static final class MetadataKeys {
        private MetadataKeys() {}

        public static final String UPSTREAM_STATUS = "upstreamStatus";
        public static final String UPSTREAM_HOST = "upstreamHost";
        public static final String UPSTREAM_PATH = "upstreamPath";
        public static final String HTTP_METHOD = "httpMethod";
        public static final String FEIGN_METHOD_KEY = "feignMethodKey";
        public static final String RETRY_AFTER_MS = "retryAfterMs";
        public static final String RETRY_AFTER_SOURCE = "retryAfterSource";
        public static final String RATE_LIMIT_MAX = "rateLimitMax";
        public static final String RATE_LIMIT_REMAINING = "rateLimitRemaining";
        public static final String RATE_LIMIT_RESET = "rateLimitReset";
    }

    /**
     * Результат извлечения {@code Retry-After}: задержка и её источник.
     * {@link #EMPTY} означает, что задержка не определена.
     */
    public record RetryAfterInfo(@Nullable Duration delay, @Nullable String source) {

        public static final RetryAfterInfo EMPTY = new RetryAfterInfo(null, null);
    }

    public static boolean isFeignException(@NonNull Object obj) {
        return ClassUtils.isInstanceOf(FEIGN_EXCEPTION_CLASS, obj);
    }

    public static boolean isRetryable(@NonNull Object obj) {
        return ClassUtils.isInstanceOf(RETRYABLE_EXCEPTION_CLASS, obj);
    }

    public static int extractFeignStatus(@NonNull Throwable throwable) {
        int[] statusHolder = {-1};
        ReflectionUtils.invokeMethod(throwable, "status", statusValue -> {
            if (statusValue instanceof Integer i) {
                statusHolder[0] = i;
            }
        });

        return statusHolder[0];
    }

    private static boolean isValidHttpStatus(int status) {
        return (status >= MIN_VALID_HTTP_STATUS) && (status <= MAX_VALID_HTTP_STATUS);
    }

    @NonNull
    public static HttpStatus mapFeignStatusToHttpStatus(boolean isRetryable, int feignStatus) {
        if (isRetryable && !isValidHttpStatus(feignStatus)) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }

        return switch (feignStatus) {
            case 400, 413, 414, 415, 422 -> HttpStatus.INTERNAL_SERVER_ERROR;
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 408, 504 -> HttpStatus.GATEWAY_TIMEOUT;
            case 409 -> HttpStatus.CONFLICT;
            case 410 -> HttpStatus.GONE;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> {
                if (isValidHttpStatus(feignStatus) && feignStatus >= 400) {
                    yield HttpStatus.BAD_GATEWAY;
                }

                yield HttpStatus.SERVICE_UNAVAILABLE;
            }
        };
    }

    @NonNull
    public static String determineErrorCode(HttpStatus httpStatus) {
        return HttpIntegrationErrorCodeUtils.determineErrorCode(httpStatus);
    }

    /**
     * Single-pass извлечение задержки {@code Retry-After}. Сначала проверяется HTTP-заголовок ответа
     * (RFC 7231 §7.1.3), затем — для retryable-исключений — {@code RetryableException.retryAfter()}.
     *
     * <p>Заголовок имеет приоритет: upstream явно сообщил клиенту, когда повторять. Источник
     * фиксируется в возвращаемом {@link RetryAfterInfo#source()} для последующей записи в diagnostic-metadata.
     */
    @NonNull
    public static RetryAfterInfo extractRetryAfterInfo(@NonNull Throwable cause, boolean isRetryable) {
        Duration fromHeader = extractRetryAfterFromHeader(cause);
        if (fromHeader != null) {
            return new RetryAfterInfo(fromHeader, RETRY_AFTER_SOURCE_HEADER);
        }

        if (!isRetryable) {
            return RetryAfterInfo.EMPTY;
        }

        Duration fromException = extractRetryAfterFromException(cause);
        if (fromException != null) {
            return new RetryAfterInfo(fromException, RETRY_AFTER_SOURCE_EXCEPTION);
        }

        return RetryAfterInfo.EMPTY;
    }

    @NonNull
    public static Map<String, String> buildDiagnosticMetadata(@NonNull Throwable cause,
                                                              @NonNull StackHintExtractor stackHintExtractor) {
        boolean isRetryable = isRetryable(cause);
        return buildDiagnosticMetadata(
                cause,
                extractFeignStatus(cause),
                extractRetryAfterInfo(cause, isRetryable),
                stackHintExtractor);
    }

    @NonNull
    public static Map<String, String> buildDiagnosticMetadata(@NonNull Throwable cause,
                                                              int feignStatus,
                                                              @NonNull RetryAfterInfo retryAfter,
                                                              @NonNull StackHintExtractor stackHintExtractor) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCause(cause, stackHintExtractor);

        enrichWithRequestDetails(cause, builder);

        if (isValidHttpStatus(feignStatus)) {
            builder.put(MetadataKeys.UPSTREAM_STATUS, Integer.toString(feignStatus));
        }

        builder.putIfPresent(MetadataKeys.FEIGN_METHOD_KEY, extractFeignMethodKey(cause));

        Duration retryDelay = retryAfter.delay();
        String retrySource = retryAfter.source();
        if (retryDelay != null && retrySource != null) {
            builder.put(MetadataKeys.RETRY_AFTER_MS, Long.toString(retryDelay.toMillis()))
                    .put(MetadataKeys.RETRY_AFTER_SOURCE, retrySource);
        }

        if (feignStatus == 429) {
            enrichWithRateLimitHeaders(cause, builder);
        }

        return builder
                .withRootCause()
                .withStackHint()
                .build();
    }

    /**
     * Извлекает HTTP-метод и компоненты URL из {@code feign.Request}.
     * CWE-209: query params не включаются; path прогоняется через {@link PathSanitizer}.
     */
    private static void enrichWithRequestDetails(@NonNull Throwable cause,
                                                 @NonNull DiagnosticMetadataBuilder builder) {
        String[] httpMethod = {null};
        String[] url = {null};

        ReflectionUtils.invokeMethod(cause, "request", request -> {
            if (request == null) {
                return;
            }

            ReflectionUtils.invokeMethod(request, "httpMethod", method -> {
                if (method instanceof Enum<?> e) {
                    httpMethod[0] = e.name();
                }
            });

            ReflectionUtils.invokeMethod(request, "url", u -> {
                if (u instanceof String s && StringUtils.hasText(s)) {
                    url[0] = s;
                }
            });
        });

        if (httpMethod[0] == null) {
            ReflectionUtils.invokeMethod(cause, "method", method -> {
                if (method instanceof Enum<?> e) {
                    httpMethod[0] = e.name();
                }
            });
        }

        builder.putIfPresent(MetadataKeys.HTTP_METHOD, httpMethod[0]);
        if (url[0] != null) {
            enrichWithUrlComponents(url[0], builder);
        }
    }

    private static void enrichWithUrlComponents(@NonNull String url,
                                                @NonNull DiagnosticMetadataBuilder builder) {
        try {
            URI uri = URI.create(url);

            builder.putIfPresent(MetadataKeys.UPSTREAM_HOST, uri.getHost())
                    .putIfPresent(MetadataKeys.UPSTREAM_PATH, PathSanitizer.sanitize(uri.getPath()));
        } catch (IllegalArgumentException ex) {
            // CWE-209: исходный URL может содержать query/userInfo с секретами — лог через LogSanitizer.
            log.debug("Invalid Feign upstream URL, skipping URL components: {}", LogSanitizer.sanitize(url));
        }
    }

    @Nullable
    private static String extractFeignMethodKey(@NonNull Throwable cause) {
        String message = cause.getMessage();
        if (!StringUtils.hasText(message)) {
            return null;
        }

        Matcher matcher = FEIGN_METHOD_KEY.matcher(message);
        if (!matcher.find()) {
            return null;
        }

        return LogSanitizer.sanitize(matcher.group(1), MAX_METHOD_KEY_LENGTH);
    }

    private static void enrichWithRateLimitHeaders(@NonNull Throwable cause,
                                                   @NonNull DiagnosticMetadataBuilder builder) {
        ReflectionUtils.invokeMethod(cause, "responseHeaders", rawHeaders -> {
            if (!(rawHeaders instanceof Map<?, ?> headers)) {
                return;
            }

            builder.putIfPresent(MetadataKeys.RATE_LIMIT_MAX, sanitizedFirstHeader(headers, "X-RateLimit-Limit"))
                    .putIfPresent(MetadataKeys.RATE_LIMIT_REMAINING, sanitizedFirstHeader(headers, "X-RateLimit-Remaining"))
                    .putIfPresent(MetadataKeys.RATE_LIMIT_RESET, sanitizedFirstHeader(headers, "X-RateLimit-Reset"));
        });
    }

    @Nullable
    private static Duration extractRetryAfterFromHeader(@NonNull Throwable cause) {
        Duration[] holder = {null};

        ReflectionUtils.invokeMethod(cause, "responseHeaders", rawHeaders -> {
            if (!(rawHeaders instanceof Map<?, ?> headers)) {
                return;
            }

            String sanitized = sanitizedFirstHeader(headers, "Retry-After");
            if (sanitized != null) {
                holder[0] = RetryAfterHeaderParser.parse(sanitized);
            }
        });

        return holder[0];
    }

    @Nullable
    private static Duration extractRetryAfterFromException(@NonNull Throwable cause) {
        Duration[] holder = {null};

        ReflectionUtils.invokeMethod(cause, "retryAfter", retryAfter -> {
            long epochMillis;
            if (retryAfter instanceof Long l) {
                epochMillis = l;
            } else if (retryAfter instanceof Date date) {
                epochMillis = date.getTime();
            } else {
                return;
            }

            long delayMs = epochMillis - System.currentTimeMillis();
            if (delayMs > 0) {
                holder[0] = Duration.ofMillis(delayMs);
            }
        });

        return holder[0];
    }

    /**
     * Находит первое непустое значение заголовка по case-insensitive имени
     * и пропускает его через {@link LogSanitizer} (CWE-117).
     */
    @Nullable
    private static String sanitizedFirstHeader(@NonNull Map<?, ?> headers,
                                               @NonNull String headerName) {
        for (Map.Entry<?, ?> entry : headers.entrySet()) {
            if (entry.getKey() instanceof String key && headerName.equalsIgnoreCase(key)) {
                Object values = entry.getValue();
                if (values instanceof Collection<?> coll && !coll.isEmpty()) {
                    Object first = coll.iterator().next();
                    if (first instanceof String s && StringUtils.hasText(s)) {
                        return LogSanitizer.sanitize(s, MAX_HEADER_LENGTH);
                    }
                }

                return null;
            }
        }

        return null;
    }
}
