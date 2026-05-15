package space.br1440.platform.errorhandling.core.advice;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import space.br1440.platform.errorhandling.core.dispatcher.MetricSnapshotFactory;
import space.br1440.platform.errorhandling.core.util.CorrelationIdResolver;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorEntryEnricher;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricSnapshot;
import space.br1440.platform.errorhandling.mvc.internal.CorrelationIdFilter;
import space.br1440.platform.errorhandling.mvc.internal.ErrorMdcContext;
import space.br1440.platform.errorhandling.mvc.internal.ExceptionDispatcher;
import space.br1440.platform.errorhandling.mvc.internal.ExceptionLogger;
import space.br1440.platform.errorhandling.mvc.internal.ProblemResponseFactory;
import space.br1440.platform.errorhandling.mvc.observability.ExceptionObservabilityHook;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.dto.ErrorEntryDTO;

import java.util.List;

@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
@SuppressWarnings("DefaultAnnotationParam")
public class GlobalExceptionHandler {

    private final ErrorHandlingProperties properties;
    private final ExceptionDispatcher dispatcher;
    private final ExceptionMetricRecorder metricRecorder;
    private final ProblemResponseFactory responseFactory;
    private final ExceptionLogger logger;
    private final CorrelationIdResolver correlationIdResolver;
    private final List<ErrorEntryEnricher> entryEnrichers;
    private final StackHintExtractor stackHintExtractor;
    private final List<ExceptionObservabilityHook> observabilityHooks;

    public GlobalExceptionHandler(@NonNull ErrorHandlingProperties properties,
                                  @NonNull List<TopExceptionHandlingStrategy> topExceptionHandlingStrategies,
                                  @NonNull UnknownExceptionHandler unknownExceptionHandler,
                                  @NonNull ExceptionMetricRecorder exceptionMetricRecorder,
                                  @NonNull ProblemResponseFactory responseFactory,
                                  @NonNull ExceptionLogger exceptionLogger,
                                  @NonNull CorrelationIdResolver correlationIdResolver,
                                  @NonNull List<ErrorEntryEnricher> entryEnrichers,
                                  @NonNull StackHintExtractor stackHintExtractor,
                                  @NonNull List<ExceptionObservabilityHook> observabilityHooks) {
        this.properties = properties;
        this.dispatcher = new ExceptionDispatcher(topExceptionHandlingStrategies, unknownExceptionHandler);
        this.metricRecorder = exceptionMetricRecorder;
        this.responseFactory = responseFactory;
        this.logger = exceptionLogger;
        this.correlationIdResolver = correlationIdResolver;
        this.entryEnrichers = List.copyOf(entryEnrichers);
        this.stackHintExtractor = stackHintExtractor;
        this.observabilityHooks = List.copyOf(observabilityHooks);
    }

    /**
     * Backward-compatible перегрузка для тестов.
     */
    public GlobalExceptionHandler(@NonNull ErrorHandlingProperties properties,
                                  @NonNull List<TopExceptionHandlingStrategy> topExceptionHandlingStrategies,
                                  @NonNull UnknownExceptionHandler unknownExceptionHandler,
                                  @NonNull ExceptionMetricRecorder exceptionMetricRecorder,
                                  @NonNull ProblemResponseFactory responseFactory,
                                  @NonNull ExceptionLogger exceptionLogger,
                                  @NonNull CorrelationIdResolver correlationIdResolver,
                                  @NonNull List<ErrorEntryEnricher> entryEnrichers,
                                  @NonNull StackHintExtractor stackHintExtractor) {
        this(properties, topExceptionHandlingStrategies, unknownExceptionHandler,
                exceptionMetricRecorder, responseFactory, exceptionLogger, correlationIdResolver,
                entryEnrichers, stackHintExtractor, List.of());
    }

    @ExceptionHandler(Exception.class)
    @NonNull
    public ResponseEntity<ErrorEntryDTO> handleException(@NonNull Exception exception,
                                                         @NonNull HttpServletRequest request) {
        String correlationId = readCorrelationIdFromRequest(request);

        try (ErrorMdcContext mdc = ErrorMdcContext.open(correlationId, exception)) {
            ErrorHandlingContext context = ErrorHandlingContext.of(properties, request.getRequestURI(), stackHintExtractor);

            long startNanos = System.nanoTime();
            ExceptionDispatcher.DispatchResult resolved = dispatcher.dispatch(exception, context);
            long durationNanos = System.nanoTime() - startNanos;

            ErrorEntry enriched = applyEnrichers(resolved.entry(), context);

            ExceptionMetricSnapshot snapshot = MetricSnapshotFactory.build(
                    resolved.strategyName(), exception, enriched, durationNanos, request.getRequestURI()
            );
            recordMetrics(snapshot, resolved.unhandled());

            mdc.enrichWithEntry(enriched);
            logger.log(exception, resolved.logPolicy(), request, enriched);

            invokeObservabilityHooks(exception, enriched, correlationId);

            return responseFactory.build(enriched, correlationId, request.getRequestURI());
        } catch (Exception handlerError) {
            log.error("Critical failure during exception handling. Original: [{}], Handler error: [{}]",
                    exception.getClass().getName(),
                    handlerError.getMessage(),
                    handlerError);
            return responseFactory.safetyNet(correlationId);
        }
    }

    @NonNull
    private ErrorEntry applyEnrichers(@NonNull ErrorEntry entry, @NonNull ErrorHandlingContext context) {
        ErrorEntry current = entry;
        for (ErrorEntryEnricher enricher : entryEnrichers) {
            current = enricher.enrich(current, context);
        }

        return current;
    }

    private void recordMetrics(@NonNull ExceptionMetricSnapshot snapshot, boolean unhandled) {
        if (unhandled) {
            metricRecorder.recordUnhandled(snapshot);
        } else {
            metricRecorder.recordHandled(snapshot);
        }
    }

    @NonNull
    private String readCorrelationIdFromRequest(@NonNull HttpServletRequest request) {
        Object cached = request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        if (cached instanceof String s && !s.isEmpty()) {
            return s;
        }

        return correlationIdResolver.resolveFromRequest(request);
    }

    private void invokeObservabilityHooks(@NonNull Exception exception,
                                          @NonNull ErrorEntry entry,
                                          @NonNull String correlationId) {
        for (ExceptionObservabilityHook hook : observabilityHooks) {
            try {
                hook.onException(exception, entry, correlationId);
            } catch (RuntimeException hookError) {
                log.warn("Observability hook [{}] failed for [{}]: {}",
                        hook.getClass().getSimpleName(),
                        exception.getClass().getName(),
                        hookError.toString());
            }
        }
    }
}
