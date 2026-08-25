package space.br1440.platform.errorhandling.core.handler;

import io.micrometer.context.ContextSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;
import space.br1440.platform.errorhandling.autoconfigure.MdcThreadLocalAccessor;
import space.br1440.platform.errorhandling.core.constants.ErrorHandlingHeaders;
import space.br1440.platform.errorhandling.core.constants.ErrorHandlingMdcKeys;
import space.br1440.platform.errorhandling.core.dispatcher.MetricSnapshotFactory;
import space.br1440.platform.errorhandling.core.util.ReactiveCorrelationIdResolver;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.core.util.http.UpstreamOutcome;
import space.br1440.platform.errorhandling.core.util.http.UpstreamResponseReader;
import space.br1440.platform.errorhandling.core.util.log.LogStatementWriter;
import space.br1440.platform.errorhandling.core.util.sanitize.LogSanitizer;
import space.br1440.platform.errorhandling.extension.ErrorEntryEnricher;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricSnapshot;
import space.br1440.platform.errorhandling.model.error_detail.dto.ErrorInfoDetailDTO;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.webflux.properties.ErrorHandlingEnricherProperties;
import space.br1440.platform.errorhandling.webflux.properties.ErrorHandlingSafetyNetProperties;
import space.br1440.platform.errorhandling.core.util.RetryabilityResolver;
import space.br1440.platform.errorhandling.http.ErrorMediaTypes;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.RequestContext;
import space.br1440.platform.errorhandling.model.dto.ErrorEntryDTO;
import space.br1440.platform.errorhandling.model.mapping.ErrorEntryToDtoMapper;

import java.io.Serial;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Центральный диспетчер исключений для Spring WebFlux.
 *
 * <h2>MDC propagation (CWE-200, CWE-117)</h2>
 * <p>Прямой вызов {@code MDC.put}/{@code MDC.remove} в реактивном pipeline <b>запрещён</b>:
 * Reactor может перепланировать pipeline на любой поток, из-за чего ThreadLocal-значения «протекают» в чужие requests.
 * Поэтому:
 * <ul>
 *   <li>Все MDC-ключи кладутся в Reactor {@link Context} единым immutable снимком под ключом {@link MdcThreadLocalAccessor#KEY}.</li>
 *   <li>{@link reactor.core.publisher.Hooks#enableAutomaticContextPropagation()}
 *       автоматически восстанавливает MDC на любом потоке pipeline для downstream-операторов и стратегий.</li>
 *   <li>В синхронной точке логирования MDC временно поднимается через
 *       {@link ReactiveMdcScope#open} в {@code try-with-resources};
 *       после закрытия scope ThreadLocal восстанавливается до до-state, что исключает утечку.</li>
 * </ul>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReactiveGlobalExceptionHandler implements WebExceptionHandler {

    private static final String SAFETY_NET_MESSAGE = "An unexpected error occurred";
    private static final String SAFETY_NET_ERROR_CODE = "INTERNAL";
    private static final String SAFETY_NET_GENERIC_DOMAIN = "internal";

    private static final String FATAL_MESSAGE = "Service temporarily unable to process the request";
    private static final String FATAL_ERROR_CODE = "UNAVAILABLE";
    private static final String UNKNOWN_STRATEGY_NAME = "unknown";
    private static final int MAX_CACHED_EXCEPTION_TYPES = 256;

    private static final Duration UPSTREAM_PARSE_TIMEOUT = Duration.ofMillis(200);

    private final AtomicBoolean cacheLimitLogged = new AtomicBoolean(false);
    private final Object strategyCacheLock = new Object();

    private final LinkedHashMap<Class<? extends Exception>, TopExceptionHandlingStrategy> strategyTypeCache =
            new LinkedHashMap<>(MAX_CACHED_EXCEPTION_TYPES, 0.75f, true) {
                @Serial
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<Class<? extends Exception>, TopExceptionHandlingStrategy> eldest) {
                    return (size() > MAX_CACHED_EXCEPTION_TYPES);
                }
            };

    private final ErrorHandlingProperties properties;
    private final ErrorHandlingEnricherProperties enricherProperties;
    private final ErrorHandlingSafetyNetProperties safetyNetProperties;
    private final List<TopExceptionHandlingStrategy> topExceptionHandlingStrategies;
    private final UnknownExceptionHandler unknownExceptionHandler;
    private final ExceptionMetricRecorder exceptionMetricRecorder;
    private final ErrorEntryToDtoMapper errorEntryToDtoMapper;
    private final Jackson2JsonEncoder jsonEncoder;
    private final String applicationName;
    private final List<ErrorEntryEnricher> entryEnrichers;
    private final StackHintExtractor stackHintExtractor;
    private final ReactiveCorrelationIdResolver correlationIdResolver;

    @Nullable
    private final Supplier<RequestContext> tracingRequestContextSupplier;

    public ReactiveGlobalExceptionHandler(@NonNull ReactiveExceptionHandlerConfig config,
                                          @NonNull ReactiveExceptionHandlerDependencies dependencies,
                                          @NonNull String applicationName) {
        ReactiveExceptionHandlerConfig handlerConfig = Objects.requireNonNull(config, "config");
        ReactiveExceptionHandlerDependencies handlerDependencies = Objects.requireNonNull(dependencies, "dependencies");

        this.applicationName = Objects.requireNonNull(applicationName, "applicationName");
        this.properties = handlerConfig.properties();
        this.enricherProperties = handlerConfig.enricherProperties();
        this.safetyNetProperties = handlerConfig.safetyNetProperties();
        this.topExceptionHandlingStrategies = handlerDependencies.strategies();
        this.unknownExceptionHandler = handlerDependencies.unknownExceptionHandler();
        this.exceptionMetricRecorder = handlerDependencies.metricRecorder();
        this.errorEntryToDtoMapper = handlerDependencies.entryToDtoMapper();
        this.jsonEncoder = handlerDependencies.jsonEncoder();
        this.entryEnrichers = handlerDependencies.entryEnrichers();
        this.stackHintExtractor = handlerDependencies.stackHintExtractor();
        this.correlationIdResolver = handlerDependencies.correlationIdResolver();
        this.tracingRequestContextSupplier = handlerDependencies.tracingRequestContextSupplier();
    }

    @NonNull
    private String resolveCorrelationId(@NonNull ServerHttpRequest request) {
        if (tracingRequestContextSupplier != null) {
            String correlationId = tracingRequestContextSupplier.get().correlationId();
            return (correlationId == null) ? "" : correlationId;
        }

        return correlationIdResolver.resolveFromRequest(request);
    }

    private void setCorrelationIdHeaderIfOwned(@NonNull ServerHttpResponse response, @NonNull String correlationId) {
        if (tracingRequestContextSupplier == null) {
            response.getHeaders().set(ErrorHandlingHeaders.X_CORRELATION_ID, correlationId);
        }
    }

    @Override
    @NonNull
    public Mono<Void> handle(@NonNull ServerWebExchange exchange, @NonNull Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();

        if (!(ex instanceof Exception exception)) {
            return handleFatalThrowable(exchange, ex);
        }

        if (response.isCommitted()) {
            log.warn("Response already committed, cannot write error for [{}]", exception.getClass().getName());
            return Mono.error(ex);
        }

        String correlationId = resolveCorrelationId(exchange.getRequest());
        String requestPath = exchange.getRequest().getPath().value();

        return Mono.deferContextual(ctxView -> {
            Map<String, String> upstreamMdc = readUpstreamMdcFromContext(ctxView);
            Map<String, String> initialMdc = initialMdcSnapshot(upstreamMdc, correlationId, exception);

            final ErrorHandlingContext context;
            final StrategyResult result;
            final long durationNanos;
            Context syntheticInitial = Context.of(MdcThreadLocalAccessor.KEY, initialMdc);

            try (ContextSnapshot.Scope ignored = ReactiveMdcScope.open(syntheticInitial)) {
                context = ErrorHandlingContext.of(properties, requestPath, stackHintExtractor);

                long startNanos = System.nanoTime();
                result = resolveEntry(exception, context);
                durationNanos = System.nanoTime() - startNanos;
            } catch (Exception handlerError) {
                log.error("Critical failure during exception handling. Original: [{}], Handler error: [{}]",
                        exception.getClass().getName(), handlerError.getMessage(), handlerError);
                return writeSafetyNetResponse(exchange, correlationId);
            }

            return deriveUpstreamContextAsync(exception, context)
                    .flatMap(derivedContext ->
                            applyEnrichersReactive(result.entry(), derivedContext)
                                    .onErrorResume(enricherChainError -> {
                                        log.warn("Enricher chain failed for [{}], using non-enriched entry: {}",
                                                exception.getClass().getName(),
                                                enricherChainError.toString());
                                        return Mono.just(result.entry());
                                    }))
                    .flatMap(entry -> writeAfterStrategy(
                            exchange, exception, result, entry, durationNanos,
                            upstreamMdc, correlationId, requestPath)
                    )
                    .onErrorResume(writeError -> {
                        log.error("Critical failure during exception handling. Original: [{}], Handler error: [{}]",
                                exception.getClass().getName(), writeError.getMessage(), writeError);
                        return writeSafetyNetResponse(exchange, correlationId);
                    })
                    .contextWrite(ctx -> ctx.put(MdcThreadLocalAccessor.KEY, initialMdc));
        });
    }

    @NonNull
    @SuppressWarnings("unchecked")
    private static Map<String, String> readUpstreamMdcFromContext(@NonNull reactor.util.context.ContextView ctxView) {
        if (ctxView.hasKey(MdcThreadLocalAccessor.KEY)) {
            Object raw = ctxView.get(MdcThreadLocalAccessor.KEY);
            if (raw instanceof Map<?, ?> map && !map.isEmpty()) {
                return (Map<String, String>) map;
            }
        }

        Map<String, String> fromThreadLocal = org.slf4j.MDC.getCopyOfContextMap();
        return (fromThreadLocal != null) ? fromThreadLocal : Collections.emptyMap();
    }

    @NonNull
    private Mono<Void> writeAfterStrategy(@NonNull ServerWebExchange exchange,
                                          @NonNull Exception exception,
                                          @NonNull StrategyResult result,
                                          @NonNull ErrorEntry entry,
                                          long durationNanos,
                                          @NonNull Map<String, String> upstreamMdc,
                                          @NonNull String correlationId,
                                          @NonNull String requestPath) {
        ExceptionMetricSnapshot snapshot = MetricSnapshotFactory.build(result.strategyName(), exception, entry, durationNanos, requestPath);
        recordMetrics(snapshot, result.unhandled());

        Map<String, String> fullMdcSnapshot = buildMdcSnapshot(upstreamMdc, exception, entry, correlationId);
        logExceptionWithMdc(exception, result.logPolicy(), exchange.getRequest(), entry, fullMdcSnapshot);

        return writeResponse(exchange, entry, correlationId, requestPath)
                .contextWrite(ctx -> ctx.put(MdcThreadLocalAccessor.KEY, fullMdcSnapshot));
    }

    @NonNull
    private Mono<ErrorHandlingContext> deriveUpstreamContextAsync(@NonNull Exception exception,
                                                                  @NonNull ErrorHandlingContext context) {
        return Mono.fromCallable(() -> resolveUpstreamOutcome(exception))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(UPSTREAM_PARSE_TIMEOUT)
                .map(context::withUpstreamOutcome)
                .onErrorResume(TimeoutException.class, ex -> {
                    exceptionMetricRecorder.recordUpstreamParseTimeout();
                    log.warn("Upstream JSON parse timeout: timeout=[{}], exception=[{}]",
                            UPSTREAM_PARSE_TIMEOUT, exception.getClass().getName());
                    return Mono.just(context);
                })
                .onErrorResume(ex -> {
                    if (ex instanceof TimeoutException) {
                        return Mono.just(context);
                    }

                    log.warn("Upstream JSON parse failed: exception=[{}], error=[{}]",
                            exception.getClass().getName(), ex.toString());
                    return Mono.just(context);
                });
    }

    @NonNull
    private static UpstreamOutcome resolveUpstreamOutcome(@NonNull Exception exception) {
        if (exception instanceof WebClientResponseException response) {
            return UpstreamResponseReader.read(
                    response.getStatusCode().value(),
                    response.getHeaders().getContentType(),
                    response.getResponseBodyAsByteArray());
        }

        return UpstreamResponseReader.fromFailure(exception);
    }

    @NonNull
    private Mono<ErrorEntry> applyEnrichersReactive(@NonNull ErrorEntry entry,
                                                    @NonNull ErrorHandlingContext context) {
        if (entryEnrichers.isEmpty()) {
            return Mono.just(entry);
        }

        Duration timeout = Duration.ofMillis(enricherProperties.getTimeoutMs());

        Mono<ErrorEntry> chain = Mono.just(entry);
        for (ErrorEntryEnricher enricher : entryEnrichers) {
            chain = chain.flatMap(current -> applySingleEnricher(enricher, current, context, timeout));
        }

        return chain;
    }

    @NonNull
    private Mono<ErrorEntry> applySingleEnricher(@NonNull ErrorEntryEnricher enricher,
                                                 @NonNull ErrorEntry errorEntry,
                                                 @NonNull ErrorHandlingContext context,
                                                 @NonNull Duration timeout) {
        Mono<ErrorEntry> work = Mono.fromCallable(() -> enricher.enrich(errorEntry, context))
                .subscribeOn(Schedulers.boundedElastic());

        return work.timeout(timeout)
                .onErrorResume(TimeoutException.class, ex -> {
                    exceptionMetricRecorder.recordEnricherTimeout(enricher.getClass());
                    log.warn("ErrorEntryEnricher timeout: enricher=[{}] timeoutMs=[{}]",
                            enricher.getClass().getName(), timeout.toMillis());
                    return Mono.just(errorEntry);
                })
                .onErrorResume(ex -> {
                    if (ex instanceof TimeoutException) {
                        return Mono.just(errorEntry);
                    }

                    log.warn("ErrorEntryEnricher failed: enricher=[{}] error=[{}]",
                            enricher.getClass().getName(), ex.toString());
                    return Mono.just(errorEntry);
                });
    }

    @NonNull
    private StrategyResult resolveEntry(@NonNull Exception exception,
                                        @NonNull ErrorHandlingContext context) {
        TopExceptionHandlingStrategy cached;
        synchronized (strategyCacheLock) {
            cached = strategyTypeCache.get(exception.getClass());
        }

        if (cached != null) {
            return applyStrategy(cached, exception, context);
        }

        for (TopExceptionHandlingStrategy strategy : topExceptionHandlingStrategies) {
            if (strategy.supports(exception)) {
                cacheStrategy(exception.getClass(), strategy);
                return applyStrategy(strategy, exception, context);
            }
        }

        ErrorEntry entry = unknownExceptionHandler.handle(exception, context);
        LogPolicy policy = unknownExceptionHandler.logPolicy(exception, entry);
        return new StrategyResult(entry, policy, UNKNOWN_STRATEGY_NAME, true);
    }

    @NonNull
    private static StrategyResult applyStrategy(@NonNull TopExceptionHandlingStrategy strategy,
                                                @NonNull Exception exception,
                                                @NonNull ErrorHandlingContext context) {
        ErrorEntry entry = strategy.handle(exception, context);
        LogPolicy policy = strategy.logPolicy(exception, entry);
        return new StrategyResult(entry, policy, strategy.getClass().getSimpleName(), false);
    }

    private void recordMetrics(@NonNull ExceptionMetricSnapshot snapshot, boolean unhandled) {
        if (unhandled) {
            exceptionMetricRecorder.recordUnhandled(snapshot);
        } else {
            exceptionMetricRecorder.recordHandled(snapshot);
        }
    }

    private void cacheStrategy(@NonNull Class<? extends Exception> exceptionType,
                               @NonNull TopExceptionHandlingStrategy strategy) {
        synchronized (strategyCacheLock) {
            strategyTypeCache.putIfAbsent(exceptionType, strategy);

            if (strategyTypeCache.size() >= MAX_CACHED_EXCEPTION_TYPES
                    && cacheLimitLogged.compareAndSet(false, true)) {
                log.warn("Exception strategy type cache reached capacity ({}) — "
                                + "LRU eviction is now active (oldest unused types will be evicted). "
                                + "This typically indicates dynamic exception class generation.",
                        MAX_CACHED_EXCEPTION_TYPES);
            }
        }
    }

    @NonNull
    private Mono<Void> writeResponse(@NonNull ServerWebExchange exchange,
                                     @NonNull ErrorEntry entry,
                                     @NonNull String correlationId,
                                     @NonNull String requestPath) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            log.warn("Response committed mid-pipeline, dropping error body for correlationId=[{}]", correlationId);
            return Mono.empty();
        }

        ErrorEntry enriched = enrichEntry(entry, correlationId, requestPath);
        ErrorEntryDTO dto = errorEntryToDtoMapper.toDto(enriched);

        response.setStatusCode(enriched.getHttpStatus());
        response.getHeaders().setContentType(ErrorMediaTypes.ERROR_RESPONSE_MEDIA_TYPE);
        setCorrelationIdHeaderIfOwned(response, correlationId);

        long retryDelaySeconds = RetryabilityResolver.retryDelayHeaderSeconds(enriched);
        if (retryDelaySeconds > 0) {
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(retryDelaySeconds));
        }

        Publisher<? extends DataBuffer> body = jsonEncoder.encode(
                Mono.just(dto),
                response.bufferFactory(),
                ResolvableType.forInstance(dto),
                ErrorMediaTypes.ERROR_RESPONSE_MEDIA_TYPE,
                Collections.emptyMap()
        );

        return response.writeWith(Flux.from(body)
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release));
    }

    @NonNull
    private ErrorEntry enrichEntry(@NonNull ErrorEntry entry,
                                   @NonNull String correlationId,
                                   @NonNull String requestPath) {
        RequestContext requestContext = (entry.getRequestContext() != null)
                ? entry.getRequestContext().withCorrelationIdIfMissing(correlationId)
                : RequestContext.builder().correlationId(correlationId).build();

        ErrorEntry.ErrorEntryBuilder builder = entry.toBuilder().requestContext(requestContext);

        if (!StringUtils.hasText(entry.getDomain())) {
            builder.domain(applicationName);
        }

        if (!StringUtils.hasText(entry.getRequestURI())) {
            builder.requestURI(requestPath);
        }

        return builder.build();
    }

    @NonNull
    private Mono<Void> handleFatalThrowable(@NonNull ServerWebExchange exchange, @NonNull Throwable fatal) {
        String correlationId = resolveCorrelationId(exchange.getRequest());

        log.error("FATAL Throwable in WebFlux pipeline — bypassing enrichers/strategies. "
                        + "Type=[{}], correlationId=[{}]",
                fatal.getClass().getName(), correlationId, fatal);

        if (exchange.getResponse().isCommitted()) {
            return Mono.error(fatal);
        }

        return writeFatalResponse(exchange, correlationId);
    }

    @NonNull
    private Mono<Void> writeFatalResponse(@NonNull ServerWebExchange exchange,
                                          @NonNull String correlationId) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            log.warn("Response committed before fatal write for correlationId=[{}]", correlationId);
            return Mono.empty();
        }

        String fatalDomain = safetyNetProperties.isExposeApplicationName()
                ? applicationName
                : SAFETY_NET_GENERIC_DOMAIN;

        ErrorEntryDTO error = ErrorEntryDTO.builder()
                .httpCode(HttpStatus.SERVICE_UNAVAILABLE.value())
                .errorCode(FATAL_ERROR_CODE)
                .message(FATAL_MESSAGE)
                .domain(fatalDomain)
                .metadata(Map.of("correlationId", correlationId))
                .detail(space.br1440.platform.errorhandling.model.error_detail.dto.ErrorInfoDetailDTO.builder()
                        .reason(FATAL_ERROR_CODE)
                        .domain(fatalDomain)
                        .build())
                .build();

        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().setContentType(ErrorMediaTypes.ERROR_RESPONSE_MEDIA_TYPE);
        setCorrelationIdHeaderIfOwned(response, correlationId);

        Publisher<? extends DataBuffer> body = jsonEncoder.encode(
                Mono.just(error),
                response.bufferFactory(),
                ResolvableType.forInstance(error),
                ErrorMediaTypes.ERROR_RESPONSE_MEDIA_TYPE,
                Collections.emptyMap()
        );

        return response.writeWith(Flux.from(body)
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release));
    }

    @NonNull
    private Mono<Void> writeSafetyNetResponse(@NonNull ServerWebExchange exchange,
                                               @NonNull String correlationId) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            log.warn("Response committed before safety-net write for correlationId=[{}]", correlationId);
            return Mono.empty();
        }

        String safetyDomain = safetyNetProperties.isExposeApplicationName()
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

        response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        response.getHeaders().setContentType(ErrorMediaTypes.ERROR_RESPONSE_MEDIA_TYPE);
        setCorrelationIdHeaderIfOwned(response, correlationId);

        Publisher<? extends DataBuffer> body = jsonEncoder.encode(
                Mono.just(error),
                response.bufferFactory(),
                ResolvableType.forInstance(error),
                ErrorMediaTypes.ERROR_RESPONSE_MEDIA_TYPE,
                Collections.emptyMap()
        );

        return response.writeWith(Flux.from(body)
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release));
    }

    @NonNull
    private static Map<String, String> initialMdcSnapshot(@NonNull Map<String, String> upstreamMdc,
                                                          @NonNull String correlationId,
                                                          @NonNull Exception exception) {
        Map<String, String> snapshot = new LinkedHashMap<>(upstreamMdc);
        snapshot.put(ErrorHandlingMdcKeys.CORRELATION_ID, correlationId);
        snapshot.put(ErrorHandlingMdcKeys.EXCEPTION_TYPE, exception.getClass().getName());
        return Collections.unmodifiableMap(snapshot);
    }

    @NonNull
    private static Map<String, String> buildMdcSnapshot(@NonNull Map<String, String> upstreamMdc,
                                                        @NonNull Exception exception,
                                                        @NonNull ErrorEntry entry,
                                                        @NonNull String correlationId) {
        Map<String, String> snapshot = new LinkedHashMap<>(upstreamMdc);

        snapshot.put(ErrorHandlingMdcKeys.CORRELATION_ID, correlationId);
        snapshot.put(ErrorHandlingMdcKeys.EXCEPTION_TYPE, exception.getClass().getName());
        snapshot.put(ErrorHandlingMdcKeys.ERROR_STATUS, String.valueOf(entry.getHttpStatus().value()));
        snapshot.put(ErrorHandlingMdcKeys.ERROR_CODE, entry.getErrorCode());
        snapshot.put(ErrorHandlingMdcKeys.ERROR_MESSAGE, entry.getMessage());

        if (StringUtils.hasText(entry.getReason())) {
            snapshot.put(ErrorHandlingMdcKeys.ERROR_REASON, entry.getReason());
        } else {
            snapshot.remove(ErrorHandlingMdcKeys.ERROR_REASON);
        }

        return Collections.unmodifiableMap(snapshot);
    }

    private void logExceptionWithMdc(@NonNull Exception exception,
                                     @NonNull LogPolicy logPolicy,
                                     @NonNull ServerHttpRequest request,
                                     @NonNull ErrorEntry entry,
                                     @NonNull Map<String, String> mdcSnapshot) {
        Context syntheticContext = Context.of(MdcThreadLocalAccessor.KEY, mdcSnapshot);

        try (ContextSnapshot.Scope ignored = ReactiveMdcScope.open(syntheticContext)) {
            logException(exception, logPolicy, request, entry);
        }
    }

    private void logException(@NonNull Exception exception,
                              @NonNull LogPolicy logPolicy,
                              @NonNull ServerHttpRequest request,
                              @NonNull ErrorEntry entry) {
        switch (properties.getLoggingStrategy()) {
            case CATEGORY -> logByPolicy(exception, logPolicy, request, entry);
            case ALL_DEBUG -> logAllDebug(exception, request);
            case ALL_ERROR -> logAllError(exception, request);
        }
    }

    private void logByPolicy(@NonNull Exception exception,
                             @NonNull LogPolicy logPolicy,
                             @NonNull ServerHttpRequest request,
                             @NonNull ErrorEntry entry) {
        String method = LogSanitizer.sanitize(request.getMethod().name());
        String uri = LogSanitizer.sanitize(request.getPath().value());
        int code = entry.getHttpStatus().value();

        String type = exception.getClass().getName();
        String msg = LogSanitizer.sanitize(exception.getMessage());

        boolean withStacktrace = properties.getStacktraceLogLevels().contains(logPolicy);

        LogStatementWriter.write(
                log, logPolicy, withStacktrace,
                method, uri, code,
                type, msg, exception
        );
    }

    private void logAllDebug(@NonNull Exception exception, @NonNull ServerHttpRequest request) {
        log.debug("[{} {}] {}: {}",
                LogSanitizer.sanitize(request.getMethod().name()),
                LogSanitizer.sanitize(request.getPath().value()),
                exception.getClass().getName(),
                LogSanitizer.sanitize(exception.getMessage()));
    }

    private void logAllError(@NonNull Exception exception, @NonNull ServerHttpRequest request) {
        log.error("[{} {}] {}: {}",
                LogSanitizer.sanitize(request.getMethod().name()),
                LogSanitizer.sanitize(request.getPath().value()),
                exception.getClass().getName(),
                LogSanitizer.sanitize(exception.getMessage()),
                exception);
    }

    private record StrategyResult(@NonNull ErrorEntry entry,
                                  @NonNull LogPolicy logPolicy,
                                  @NonNull String strategyName,
                                  boolean unhandled) {
    }
}
