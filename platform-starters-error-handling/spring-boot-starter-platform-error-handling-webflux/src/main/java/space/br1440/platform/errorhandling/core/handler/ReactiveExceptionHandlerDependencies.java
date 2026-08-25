package space.br1440.platform.errorhandling.core.handler;

import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.ReactiveCorrelationIdResolver;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorEntryEnricher;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.model.RequestContext;
import space.br1440.platform.errorhandling.model.mapping.ErrorEntryToDtoMapper;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public record ReactiveExceptionHandlerDependencies(@NonNull List<TopExceptionHandlingStrategy> strategies,
                                                   @NonNull UnknownExceptionHandler unknownExceptionHandler,
                                                   @NonNull ExceptionMetricRecorder metricRecorder,
                                                   @NonNull ErrorEntryToDtoMapper entryToDtoMapper,
                                                   @NonNull Jackson2JsonEncoder jsonEncoder,
                                                   @NonNull List<ErrorEntryEnricher> entryEnrichers,
                                                   @NonNull StackHintExtractor stackHintExtractor,
                                                   @NonNull ReactiveCorrelationIdResolver correlationIdResolver,
                                                   @Nullable Supplier<RequestContext> tracingRequestContextSupplier) {
    public ReactiveExceptionHandlerDependencies {
        Objects.requireNonNull(strategies, "strategies");
        Objects.requireNonNull(unknownExceptionHandler, "unknownExceptionHandler");
        Objects.requireNonNull(metricRecorder, "metricRecorder");
        Objects.requireNonNull(entryToDtoMapper, "entryToDtoMapper");
        Objects.requireNonNull(jsonEncoder, "jsonEncoder");
        Objects.requireNonNull(entryEnrichers, "entryEnrichers");
        Objects.requireNonNull(stackHintExtractor, "stackHintExtractor");
        Objects.requireNonNull(correlationIdResolver, "correlationIdResolver");
    }
}
