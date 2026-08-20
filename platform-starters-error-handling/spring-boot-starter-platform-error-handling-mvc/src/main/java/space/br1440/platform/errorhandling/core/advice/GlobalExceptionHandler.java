package space.br1440.platform.errorhandling.core.advice;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import space.br1440.platform.errorhandling.core.constants.ErrorHandlingHeaders;
import space.br1440.platform.errorhandling.core.dispatcher.MetricSnapshotFactory;
import space.br1440.platform.errorhandling.core.util.CorrelationIdResolver;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.http.UpstreamOutcome;
import space.br1440.platform.errorhandling.core.util.http.UpstreamResponseReader;
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
import space.br1440.platform.errorhandling.model.RequestContext;
import space.br1440.platform.errorhandling.model.dto.ErrorEntryDTO;

import java.util.List;
import java.util.function.Supplier;

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

    @Nullable
    private final Supplier<RequestContext> tracingRequestContextSupplier;

    public GlobalExceptionHandler(@NonNull ErrorHandlingProperties properties,
                                  @NonNull List<TopExceptionHandlingStrategy> topExceptionHandlingStrategies,
                                  @NonNull UnknownExceptionHandler unknownExceptionHandler,
                                  @NonNull ExceptionMetricRecorder exceptionMetricRecorder,
                                  @NonNull ProblemResponseFactory responseFactory,
                                  @NonNull ExceptionLogger exceptionLogger,
                                  @NonNull CorrelationIdResolver correlationIdResolver,
                                  @NonNull List<ErrorEntryEnricher> entryEnrichers,
                                  @NonNull StackHintExtractor stackHintExtractor,
                                  @NonNull List<ExceptionObservabilityHook> observabilityHooks,
                                  @Nullable Supplier<RequestContext> tracingRequestContextSupplier) {
        this.properties = properties;
        this.dispatcher = new ExceptionDispatcher(topExceptionHandlingStrategies, unknownExceptionHandler);
        this.metricRecorder = exceptionMetricRecorder;
        this.responseFactory = responseFactory;
        this.logger = exceptionLogger;
        this.correlationIdResolver = correlationIdResolver;
        this.entryEnrichers = List.copyOf(entryEnrichers);
        this.stackHintExtractor = stackHintExtractor;
        this.observabilityHooks = List.copyOf(observabilityHooks);
        this.tracingRequestContextSupplier = tracingRequestContextSupplier;
    }

    @ExceptionHandler(Exception.class)
    @NonNull
    public ResponseEntity<ErrorEntryDTO> handleException(@NonNull Exception exception,
                                                         @NonNull HttpServletRequest request) {
        String correlationId = readCorrelationIdFromRequest(request);
        boolean joint = tracingRequestContextSupplier != null;

        try (ErrorMdcContext mdc = ErrorMdcContext.open(correlationId, exception, !joint)) {
            ErrorHandlingContext context = ErrorHandlingContext.of(properties, request.getRequestURI(), stackHintExtractor);

            long startNanos = System.nanoTime();
            ExceptionDispatcher.DispatchResult resolved = dispatcher.dispatch(exception, context);
            long durationNanos = (System.nanoTime() - startNanos);

            UpstreamOutcome upstreamOutcome = UpstreamResponseReader.fromFailure(exception);
            ErrorHandlingContext enrichmentContext = context.withUpstreamOutcome(upstreamOutcome);
            ErrorEntry enriched = applyEnrichers(resolved.entry(), enrichmentContext);

            ExceptionMetricSnapshot snapshot = MetricSnapshotFactory.build(
                    resolved.strategyName(), exception, enriched, durationNanos, request.getRequestURI()
            );
            recordMetrics(snapshot, resolved.unhandled());

            mdc.enrichWithEntry(enriched);
            logger.log(exception, resolved.logPolicy(), request, enriched);

            invokeObservabilityHooks(exception, enriched, correlationId);

            ResponseEntity<ErrorEntryDTO> response = responseFactory.build(enriched, correlationId, request.getRequestURI());
            return withCorrelationHeaderIfOwned(response, correlationId, joint);
        } catch (Exception handlerError) {
            log.error("Critical failure during exception handling. Original: [{}], Handler error: [{}]",
                    exception.getClass().getName(),
                    handlerError.getMessage(),
                    handlerError);
            return withCorrelationHeaderIfOwned(responseFactory.safetyNet(correlationId), correlationId, joint);
        }
    }

    @NonNull
    private static ResponseEntity<ErrorEntryDTO> withCorrelationHeaderIfOwned(@NonNull ResponseEntity<ErrorEntryDTO> response,
                                                                              @NonNull String correlationId,
                                                                              boolean joint) {
        if (joint) {
            return response;
        }

        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .header(ErrorHandlingHeaders.X_CORRELATION_ID, correlationId)
                .body(response.getBody());
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

        if (tracingRequestContextSupplier != null) {
            String correlationId = tracingRequestContextSupplier.get().correlationId();
            return (correlationId == null) ? "" : correlationId;
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
