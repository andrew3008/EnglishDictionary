package space.br1440.platform.errorhandling.core.strategy.cause.network;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;
import space.br1440.platform.errorhandling.extension.ErrorClassification;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.ClassUtils;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.ReflectionUtils;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.core.util.sanitize.LogSanitizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Стратегия обработки timeout-исключений как первопричины бизнес-исключений.
 *
 * <h2>Обрабатываемые исключения</h2>
 * <pre>
 * java.util.concurrent.TimeoutException                        → 504 ASYNC_TIMEOUT        retryable
 *   {@code Future.get(timeout)}, {@code CompletableFuture.orTimeout()},
 *   {@code Mono.timeout()}, {@code ExecutorService.invokeAll(timeout)},
 *   gRPC client deadlines, Resilience4j {@code TimeLimiter}.
 *
 * io.netty.handler.timeout.TimeoutException                    → 504 NETTY_TIMEOUT        retryable
 *   ├── ReadTimeoutException                                   → 504 NETTY_READ_TIMEOUT   retryable
 *   └── WriteTimeoutException                                  → 504 NETTY_WRITE_TIMEOUT  retryable
 *   WebClient/Reactor Netty, gRPC, любые reactive HTTP клиенты на Netty.
 *
 * o.s.core.task.TaskTimeoutException                           → 503 TASK_TIMEOUT         retryable
 *   {@code @Async}, {@code ThreadPoolTaskExecutor},
 *   Spring Integration channel dispatchers.
 *
 * o.s.transaction.TransactionTimedOutException                 → 503 TRANSACTION_TIMEOUT  retryable
 *   {@code @Transactional(timeout=N)}, {@code PlatformTransactionManager}.
 *   503 (а не 504): транзакционный таймаут — это перегрузка/lock contention
 *   нашей БД/нашего bounded context, а не сбой апстрим-шлюза.
 *
 * o.s.web.context.request.async.AsyncRequestTimeoutException   → 503 ASYNC_REQUEST_TIMEOUT retryable
 *   Spring MVC async dispatch ({@code DeferredResult}, {@code Callable},
 *   reactive return types в MVC). Таймаут истекает на стороне сервиса —
 *   ответ клиенту не успевает сформироваться. 503: сервис временно
 *   не справляется с обработкой.
 * </pre>
 *
 * <h3>Не обрабатывается здесь (намеренно)</h3>
 * <ul>
 *   <li>{@code o.s.dao.QueryTimeoutException} — обрабатывается
 *       {@code DataAccessExceptionStrategy} (order=150) <b>раньше</b>
 *       {@link RootCauseExceptionStrategyOrder#TIMEOUT} (335). У DataAccess
 *       специализированный SQL-state маппинг, более ценный, чем generic
 *       timeout-категория. Это design decision.</li>
 *   <li>Доменные таймауты Kafka ({@code o.a.k.c.errors.TimeoutException}),
 *       Redis Lettuce ({@code RedisCommandTimeoutException}), Mongo —
 *       обрабатываются специализированными стратегиями каждого домена,
 *       у них есть broker/коннекторная диагностика, недоступная здесь.</li>
 * </ul>
 *
 * <h2>diagnosticMetadata (SRE/разработчики, не попадает в DTO)</h2>
 * <ul>
 *   <li>{@code causeMessage} — сообщение исключения после {@link LogSanitizer}
 *       (truncated до {@value #MAX_CAUSE_MESSAGE_LENGTH}).</li>
 *   <li>{@code configuredTimeoutMs} — настроенный таймаут в миллисекундах.
 *       Извлекается regex-парсингом сообщения исключения. Поскольку JDK/Netty
 *       не выставляют публичных {@code getTimeoutValue()}/{@code getDurationInMillis()},
 *       полагаемся на текстовые паттерны производителей библиотек (HikariCP, Lettuce,
 *       Reactor, gRPC, Resilience4j).</li>
 *   <li>{@code operationHint} — эвристика типа операции по тексту сообщения:
 *       {@code READ}, {@code WRITE}, {@code CONNECT}, {@code POOL_ACQUIRE},
 *       {@code TRANSACTION}, {@code BLOCKING}, {@code REQUEST}.</li>
 *   <li>{@code transactionDeadlineEpochMs} — deadline транзакции (epoch ms),
 *       извлекается через структурированный {@code TransactionTimedOutException.getDeadline()}.
 *       Только для {@code TRANSACTION_TIMEOUT}. Long-строка — пригодна для
 *       сортировки и фильтрации в Loki/Elasticsearch.</li>
 *   <li>{@code asyncSource} — источник async-таймаута: {@code COMPLETABLE_FUTURE},
 *       {@code REACTOR}, {@code FUTURE_GET}, {@code GRPC_DEADLINE}, {@code RESILIENCE4J}.
 *       Эвристика по {@link StackHintExtractor stackHint}. Только для {@code ASYNC_TIMEOUT}.</li>
 *   <li>{@code rootCauseClass} — FQCN глубинной причины (если отличается, глубина до 5).</li>
 *   <li>{@code rootCauseMessage} — сообщение глубинной причины (truncated).</li>
 *   <li>{@code stackHint} — 1-3 значимых фрейма стектрейса.</li>
 * </ul>
 * <p>{@code timeoutType} и {@code timeoutLayer} <b>не дублируются</b> в diagnosticMetadata —
 * первое тождественно {@code reason}, второе уже передаётся через {@code clientMetadata}.
 *
 * <h2>clientMetadata</h2>
 * <ul>
 *   <li>{@code timeoutLayer} — {@code ASYNC}/{@code TRANSPORT}/{@code TASK}/{@code TRANSACTION}/
 *       {@code REQUEST}/{@code UNKNOWN}. Категория слоя, на котором сработал таймаут.</li>
 * </ul>
 * <p>Поле {@code retryable} <b>не</b> публикуется в clientMetadata — retry-семантика
 * передаётся через {@link RetryInfoDetail} в {@code details} согласно RFC 9457.
 *
 * <h2>Retryability</h2>
 * <p>Все таймауты по определению {@code retryable=true}: если операция не
 * завершилась за отведённое время, повторная попытка может завершиться успешно
 * при сниженной нагрузке. Persistent overload определяется на уровне retry
 * policy (exponential backoff + jitter), а не на уровне классификации.
 *
 * <h2>Безопасность (CWE-209, CWE-117, CVE-2021-42574)</h2>
 * <p>Сообщения timeout-исключений намеренно включаются <b>только</b> в
 * {@code diagnosticMetadata} (не в клиентский DTO) и <b>обязательно</b>
 * проходят через {@link LogSanitizer} перед записью: удаляются CR/LF/tab,
 * NUL и Unicode bidirectional override (U+202A..U+202E, U+2066..U+2069),
 * длина обрезается до {@value #MAX_CAUSE_MESSAGE_LENGTH} символов. Это
 * защищает индексы Loki/Elasticsearch/SIEM от log-injection и Trojan Source.
 *
 * @see space.br1440.platform.errorhandling.core.strategy.cause.network.DnsResolutionExceptionStrategy
 * @see space.br1440.platform.errorhandling.core.strategy.cause.network.HttpIntegrationExceptionStrategy
 */
public class TimeoutExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    /**
     * Лимит длины {@code causeMessage}/{@code rootCauseMessage} — согласован с
     * {@link DiagnosticMetadataBuilder#DEFAULT_MAX_MESSAGE_LENGTH} (=200) и с
     * соседними network/messaging-стратегиями ({@code KafkaBrokerExceptionStrategy},
     * {@code DnsResolutionExceptionStrategy}). Сообщения timeout не специфично
     * длиннее, чем у других интеграционных исключений.
     */
    private static final int MAX_CAUSE_MESSAGE_LENGTH = DiagnosticMetadataBuilder.DEFAULT_MAX_MESSAGE_LENGTH;

    /**
     * Regex для извлечения значения таймаута в миллисекундах из сообщения исключения.
     *
     * <p>Покрывает распространённые форматы:
     * <ul>
     *   <li>{@code "timed out after 30000ms"} (HikariCP, general)</li>
     *   <li>{@code "Timeout after 5000 MILLISECONDS"} (Lettuce, WebClient)</li>
     *   <li>{@code "timeout of 30000 millis"} (general)</li>
     *   <li>{@code "exceeded deadline: 5000ms"} (gRPC, Resilience4j)</li>
     * </ul>
     *
     * <p>Ищет 2+ значные числа ({@code \d{2,}}) для минимизации false positives
     * (timeout &lt; 10ms — маловероятен в production).
     */
    static final Pattern TIMEOUT_MS_FROM_MESSAGE = Pattern.compile(
            "(\\d{2,})\\s*(?:ms|millis(?:econds)?)", Pattern.CASE_INSENSITIVE);

    /**
     * Regex для извлечения значения таймаута в секундах из сообщения.
     *
     * <p>Покрывает: {@code "after 30s"}, {@code "timeout 5 seconds"},
     * {@code "exceeded 1.5 sec"}.
     */
    static final Pattern TIMEOUT_SEC_FROM_MESSAGE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(?:seconds?|sec\\b)", Pattern.CASE_INSENSITIVE);

    /**
     * Operation hint tokens — все используют word boundaries для исключения
     * ложных срабатываний на подстроках вроде {@code thread} (содержит "read"),
     * {@code rewrite} (содержит "write"), {@code defended} (содержит "send").
     */
    private static final Pattern READ_TOKEN = Pattern.compile("\\bread\\b");
    private static final Pattern WRITE_OR_SEND_TOKEN = Pattern.compile("\\b(?:write|send)\\b");
    private static final Pattern POOL_TOKEN = Pattern.compile("\\b(?:acquire|pool|borrow)\\b");
    private static final Pattern CONNECT_TOKEN = Pattern.compile("\\bconnect\\b");
    private static final Pattern TRANSACTION_TOKEN = Pattern.compile("\\btransaction\\b");
    private static final Pattern BLOCKING_TOKEN = Pattern.compile("\\bblocking\\b");
    private static final Pattern REQUEST_TOKEN = Pattern.compile("\\b(?:request|invoke|call)\\b");

    private static final ClassLoader LOADER = TimeoutExceptionStrategy.class.getClassLoader();

    @Nullable
    private static final Class<?> NETTY_READ_TIMEOUT =
            ReflectionUtils.loadOptionalClass("io.netty.handler.timeout.ReadTimeoutException", LOADER);
    @Nullable
    private static final Class<?> NETTY_WRITE_TIMEOUT =
            ReflectionUtils.loadOptionalClass("io.netty.handler.timeout.WriteTimeoutException", LOADER);
    @Nullable
    private static final Class<?> NETTY_TIMEOUT =
            ReflectionUtils.loadOptionalClass("io.netty.handler.timeout.TimeoutException", LOADER);

    @Nullable
    private static final Class<?> TASK_TIMEOUT =
            ReflectionUtils.loadOptionalClass("org.springframework.core.task.TaskTimeoutException", LOADER);

    @Nullable
    private static final Class<?> TRANSACTION_TIMEOUT =
            ReflectionUtils.loadOptionalClass("org.springframework.transaction.TransactionTimedOutException", LOADER);

    /**
     * Spring MVC async dispatch timeout. Опциональный класс, грузится через
     * reflection — модуль не имеет hard-зависимости от {@code spring-webmvc}.
     */
    @Nullable
    private static final Class<?> ASYNC_REQUEST_TIMEOUT =
            ReflectionUtils.loadOptionalClass("org.springframework.web.context.request.async.AsyncRequestTimeoutException", LOADER);

    public TimeoutExceptionStrategy(@NonNull StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.TIMEOUT, stackHintExtractor);
    }

    /**
     * Все таймауты → {@link LogPolicy#ERROR}.
     *
     * <p>Каждый таймаут в распределённой системе — потенциальное нарушение SLA:
     * запрос, который должен был быть обработан в рамках deadline, не был обслужен.
     * {@code WARN} маскирует production-инциденты, при которых нарушаются SLO
     * по латентности. Даже транзиентные таймауты требуют внимания SRE для
     * выявления паттернов деградации ёмкости и задержек.
     */
    @Override
    @NonNull
    public @NotNull LogPolicy logPolicy(@NonNull Exception exception,
                                        @NonNull ErrorEntry errorEntry) {
        return LogPolicy.ERROR;
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return isTimeoutException(cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        TimeoutErrorType errorType = classify(cause);

        List<ErrorDetail> details = new ArrayList<>();
        if (errorType.retryability() == Retryability.RETRYABLE) {
            details.add(new RetryInfoDetail());
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(errorType.name())
                .domain("timeout")
                .clientMetadata(buildClientMetadata(errorType))
                .diagnosticMetadata(buildDiagnosticMetadata(cause, errorType))
                .build();
        details.add(info);

        return errorType.toResolution(details);
    }

    private static boolean isTimeoutException(@NonNull Throwable cause) {
        if (cause instanceof TimeoutException) {
            return true;
        }

        if (ClassUtils.isInstanceOf(NETTY_TIMEOUT, cause)) {
            return true;
        }

        if (ClassUtils.isInstanceOf(TASK_TIMEOUT, cause)) {
            return true;
        }

        if (ClassUtils.isInstanceOf(TRANSACTION_TIMEOUT, cause)) {
            return true;
        }

        return ClassUtils.isInstanceOf(ASYNC_REQUEST_TIMEOUT, cause);
    }

    /**
     * Порядок проверок критичен: подклассы Netty {@code ReadTimeoutException}/
     * {@code WriteTimeoutException} должны проверяться <b>раньше</b> базового
     * {@code io.netty.handler.timeout.TimeoutException}, иначе они будут
     * проглочены родительским типом и потеряют специализированную диагностику.
     */
    @NonNull
    private static TimeoutErrorType classify(@NonNull Throwable cause) {
        if (ClassUtils.isInstanceOf(NETTY_READ_TIMEOUT, cause)) {
            return TimeoutErrorType.NETTY_READ_TIMEOUT;
        }

        if (ClassUtils.isInstanceOf(NETTY_WRITE_TIMEOUT, cause)) {
            return TimeoutErrorType.NETTY_WRITE_TIMEOUT;
        }

        if (ClassUtils.isInstanceOf(NETTY_TIMEOUT, cause)) {
            return TimeoutErrorType.NETTY_TIMEOUT;
        }

        if (ClassUtils.isInstanceOf(TASK_TIMEOUT, cause)) {
            return TimeoutErrorType.TASK_TIMEOUT;
        }

        if (ClassUtils.isInstanceOf(TRANSACTION_TIMEOUT, cause)) {
            return TimeoutErrorType.TRANSACTION_TIMEOUT;
        }

        if (ClassUtils.isInstanceOf(ASYNC_REQUEST_TIMEOUT, cause)) {
            return TimeoutErrorType.ASYNC_REQUEST_TIMEOUT;
        }

        if (cause instanceof TimeoutException) {
            return TimeoutErrorType.ASYNC_TIMEOUT;
        }

        return TimeoutErrorType.GENERIC_TIMEOUT;
    }

    @NonNull
    private static Map<String, String> buildClientMetadata(@NonNull TimeoutErrorType errorType) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("timeoutLayer", errorType.layer());
        return Collections.unmodifiableMap(metadata);
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull Throwable cause,
                                                        @NonNull TimeoutErrorType errorType) {
        // forCauseRaw: не пишем exceptionClass — он избыточен с reason/rootCauseClass
        // (ср. KafkaBrokerExceptionStrategy, KafkaSerializationExceptionStrategy).
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor);

        String causeMessage = cause.getMessage();
        if (StringUtils.hasText(causeMessage)) {
            // CWE-117/CVE-2021-42574: санитизация для индексации в Loki/SIEM
            builder.put("causeMessage", LogSanitizer.sanitize(causeMessage, MAX_CAUSE_MESSAGE_LENGTH));
        }

        Long configuredTimeoutMs = extractTimeoutFromMessage(causeMessage);
        if (configuredTimeoutMs != null) {
            builder.put("configuredTimeoutMs", String.valueOf(configuredTimeoutMs));
        }

        String operationHint = extractOperationHint(cause);
        if (operationHint != null) {
            builder.put("operationHint", operationHint);
        }

        if (errorType == TimeoutErrorType.TRANSACTION_TIMEOUT) {
            enrichTransactionDeadline(cause, builder);
        }

        if (errorType == TimeoutErrorType.ASYNC_TIMEOUT) {
            // stackHint вычисляется один раз тут (для asyncSource) и переиспользуется
            // в withStackHint() ниже через builder'ов кэш.
            String stackHint = stackHintExtractor.extractStackHint(cause);
            enrichAsyncSource(stackHint, builder);
        }

        return builder
                .withRootCause(CauseChainDepth.DEFAULT, MAX_CAUSE_MESSAGE_LENGTH)
                .withStackHint()
                .build();
    }

    /**
     * Извлечение значения таймаута из текста сообщения.
     *
     * <p>Структурированные геттеры ({@code getTimeoutValue()},
     * {@code getDurationInMillis()}) не предоставляются ни одним из перехватываемых
     * исключений (JDK {@link TimeoutException}, Netty {@code TimeoutException},
     * {@code TransactionTimedOutException}, {@code AsyncRequestTimeoutException}),
     * поэтому единственный надёжный путь — regex по сообщению.
     */
    @Nullable
    static Long extractTimeoutFromMessage(@Nullable String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }

        Matcher msMatcher = TIMEOUT_MS_FROM_MESSAGE.matcher(message);
        if (msMatcher.find()) {
            try {
                long ms = Long.parseLong(msMatcher.group(1));
                if (ms > 0) {
                    return ms;
                }
            } catch (NumberFormatException ignored) {
                // паттерн допускает большие числа — ловим overflow на parseLong
            }
        }

        Matcher secMatcher = TIMEOUT_SEC_FROM_MESSAGE.matcher(message);
        if (secMatcher.find()) {
            try {
                double sec = Double.parseDouble(secMatcher.group(1));
                if (sec > 0) {
                    return Math.round(sec * 1000);
                }
            } catch (NumberFormatException ignored) {
                // защита от malformed double, хотя паттерн его не пропустит
            }
        }

        return null;
    }

    @Nullable
    static String extractOperationHint(@NonNull Throwable cause) {
        String message = cause.getMessage();
        if (!StringUtils.hasText(message)) {
            return null;
        }

        String lower = message.toLowerCase();

        if (READ_TOKEN.matcher(lower).find()) {
            return "READ";
        }

        if (WRITE_OR_SEND_TOKEN.matcher(lower).find()) {
            return "WRITE";
        }

        if (POOL_TOKEN.matcher(lower).find()) {
            return "POOL_ACQUIRE";
        }

        if (CONNECT_TOKEN.matcher(lower).find()) {
            return "CONNECT";
        }

        if (TRANSACTION_TOKEN.matcher(lower).find()) {
            return "TRANSACTION";
        }

        if (BLOCKING_TOKEN.matcher(lower).find()) {
            return "BLOCKING";
        }

        if (REQUEST_TOKEN.matcher(lower).find()) {
            return "REQUEST";
        }

        return null;
    }

    /**
     * Извлекает {@code deadline} из {@code TransactionTimedOutException} через
     * структурированный геттер {@code getDeadline() : long} (epoch ms).
     *
     * <p>Структурированное значение пригодно для сортировки и диапазонных
     * фильтров в Loki/Elasticsearch — в отличие от ранее использовавшегося
     * {@code Date.toString()}, который локалезависим и не сравним.
     */
    private static void enrichTransactionDeadline(@NonNull Throwable cause,
                                                  @NonNull DiagnosticMetadataBuilder builder) {
        ReflectionUtils.invokeMethod(cause, "getDeadline", val -> {
            if (val instanceof Long ms && ms > 0L) {
                builder.put("transactionDeadlineEpochMs", String.valueOf(ms));
            }
        });
    }

    /**
     * Эвристика источника async-таймаута по {@link StackHintExtractor stackHint}.
     *
     * <p>Покрытие современного Java-стека:
     * <ul>
     *   <li>{@code COMPLETABLE_FUTURE} — JDK {@code CompletableFuture.orTimeout},
     *       {@code completeOnTimeout}, {@code Delayer}.</li>
     *   <li>{@code REACTOR} — Project Reactor ({@code Mono.timeout}, {@code Flux.timeout}).</li>
     *   <li>{@code GRPC_DEADLINE} — gRPC client deadline (io.grpc.Deadline,
     *       io.grpc.stub.ClientCalls).</li>
     *   <li>{@code RESILIENCE4J} — Resilience4j {@code TimeLimiter}
     *       (io.github.resilience4j.timelimiter).</li>
     *   <li>{@code FUTURE_GET} — классические {@code Future.get(timeout)} /
     *       {@code ExecutorService.invokeAll}.</li>
     * </ul>
     */
    private static void enrichAsyncSource(@Nullable String stackHint,
                                          @NonNull DiagnosticMetadataBuilder builder) {
        if (stackHint == null) {
            return;
        }

        String lower = stackHint.toLowerCase();

        if (lower.contains("io.grpc.") || lower.contains("clientcalls") || lower.contains("deadline")) {
            builder.put("asyncSource", "GRPC_DEADLINE");
        } else if (lower.contains("resilience4j") || lower.contains("timelimiter")) {
            builder.put("asyncSource", "RESILIENCE4J");
        } else if (lower.contains("completablefuture") || lower.contains("ortimeout") || lower.contains("completeontimeout")) {
            builder.put("asyncSource", "COMPLETABLE_FUTURE");
        } else if (lower.contains("reactor.") || lower.contains("mono.timeout") || lower.contains("flux.timeout")) {
            builder.put("asyncSource", "REACTOR");
        } else if (lower.contains("future.get") || lower.contains("executorservice")) {
            builder.put("asyncSource", "FUTURE_GET");
        }
    }

    /**
     * Локальное вспомогательное значение глубины cause-chain.
     * Согласовано с другими network-стратегиями ({@code DnsResolutionExceptionStrategy},
     * {@code SslExceptionStrategy}).
     */
    private static final class CauseChainDepth {
        private static final int DEFAULT = 5;
    }

    enum TimeoutErrorType implements ErrorClassification {

        ASYNC_TIMEOUT(
                HttpStatus.GATEWAY_TIMEOUT,
                Retryability.RETRYABLE,
                "ASYNC",
                "An asynchronous operation did not complete within the configured timeout. "
                        + "The downstream service or background task may be overloaded or unresponsive."
        ),

        NETTY_READ_TIMEOUT(
                HttpStatus.GATEWAY_TIMEOUT,
                Retryability.RETRYABLE,
                "TRANSPORT",
                "No response data was received from the upstream service within "
                        + "the configured read timeout. The service may be overloaded "
                        + "or processing a long-running operation."
        ),

        NETTY_WRITE_TIMEOUT(
                HttpStatus.GATEWAY_TIMEOUT,
                Retryability.RETRYABLE,
                "TRANSPORT",
                "Data could not be sent to the upstream service within the configured "
                        + "write timeout. The network may be congested or the service "
                        + "is applying back-pressure."
        ),

        NETTY_TIMEOUT(
                HttpStatus.GATEWAY_TIMEOUT,
                Retryability.RETRYABLE,
                "TRANSPORT",
                "A network I/O operation timed out on the Netty transport layer."
        ),

        TASK_TIMEOUT(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "TASK",
                "An asynchronous task did not complete within the configured "
                        + "execution timeout. The service may be under heavy load."
        ),

        /**
         * 503 (а не 504): транзакционный таймаут — это перегрузка/lock contention
         * на стороне нашей БД, не сбой апстрим-шлюза. Семантически это «сервис
         * временно не справляется», что в индустрии (Resilience4j, Hystrix,
         * Spring Cloud Gateway) маппится на 503.
         */
        TRANSACTION_TIMEOUT(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "TRANSACTION",
                "A database transaction did not complete within the configured "
                        + "timeout. The database may be under heavy load or "
                        + "experiencing lock contention."
        ),

        /**
         * Spring MVC async dispatch ({@code DeferredResult}, {@code Callable},
         * reactive return types в Servlet-стеке) не успел завершиться в пределах
         * {@code spring.mvc.async.request-timeout}. 503 — сервис не успел
         * сформировать ответ; это внутренняя перегрузка, не апстрим.
         */
        ASYNC_REQUEST_TIMEOUT(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "REQUEST",
                "The service could not produce an async response within the "
                        + "configured request timeout. The application may be "
                        + "under heavy load or a downstream call exceeded its budget."
        ),

        GENERIC_TIMEOUT(
                HttpStatus.GATEWAY_TIMEOUT,
                Retryability.RETRYABLE,
                "UNKNOWN",
                "An operation did not complete within the configured timeout."
        );

        private final HttpStatus httpStatus;
        private final Retryability retryability;
        private final String layer;
        private final String message;

        TimeoutErrorType(@NonNull HttpStatus httpStatus,
                         @NonNull Retryability retryability,
                         @NonNull String layer,
                         @NonNull String message) {
            this.httpStatus = httpStatus;
            this.retryability = retryability;
            this.layer = layer;
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

        @NonNull
        String layer() {
            return layer;
        }

        @Override
        @NonNull
        public String message() {
            return message;
        }
    }
}
