package space.br1440.platform.errorhandling.webflux.testing.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.handler.ReactiveExceptionHandlerConfig;
import space.br1440.platform.errorhandling.core.handler.ReactiveExceptionHandlerDependencies;
import space.br1440.platform.errorhandling.core.handler.ReactiveGlobalExceptionHandler;
import space.br1440.platform.errorhandling.core.util.ReactiveCorrelationIdResolver;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorEntryEnricher;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.metrics.NoopExceptionMetricRecorder;
import space.br1440.platform.errorhandling.model.RequestContext;
import space.br1440.platform.errorhandling.model.mapping.ErrorEntryToDtoMapper;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.webflux.properties.ErrorHandlingEnricherProperties;
import space.br1440.platform.errorhandling.webflux.properties.ErrorHandlingSafetyNetProperties;
import space.br1440.platform.errorhandling.webflux.testing.support.TestUnknownExceptionHandler;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class ReactiveGlobalExceptionHandlerTestFixture {

    private ReactiveGlobalExceptionHandlerTestFixture() {
    }

    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    @NonNull
    public static ReactiveGlobalExceptionHandler build(@NonNull ErrorHandlingProperties properties,
                                                       @NonNull List<TopExceptionHandlingStrategy> strategies,
                                                       @NonNull ExceptionMetricRecorder recorder,
                                                       @NonNull String applicationName) {
        return builder()
                .properties(properties)
                .strategies(strategies)
                .recorder(recorder)
                .applicationName(applicationName)
                .build();
    }

    public static final class Builder {
        private ErrorHandlingProperties properties = new ErrorHandlingProperties();
        private ErrorHandlingEnricherProperties enricherProperties = new ErrorHandlingEnricherProperties();
        private ErrorHandlingSafetyNetProperties safetyNetProperties = new ErrorHandlingSafetyNetProperties();
        private UnknownExceptionHandler unknownHandler = new TestUnknownExceptionHandler();
        private ExceptionMetricRecorder recorder = new NoopExceptionMetricRecorder();
        private String applicationName = "test-app";
        private List<TopExceptionHandlingStrategy> strategies = List.of();
        private List<ErrorEntryEnricher> entryEnrichers = Collections.emptyList();
        private StackHintExtractor stackHintExtractor = new StackHintExtractor(false, List.of());
        private final Jackson2JsonEncoder jsonEncoder = new Jackson2JsonEncoder(new ObjectMapper());
        private final ErrorEntryToDtoMapper entryToDtoMapper = new ErrorEntryToDtoMapper();
        private ReactiveCorrelationIdResolver correlationIdResolverOverride;
        private Supplier<RequestContext> tracingRequestContextSupplier;

        private static <T> T req(@NonNull T value, @NonNull String name) {
            return Objects.requireNonNull(value, name);
        }

        @NonNull
        public Builder properties(@NonNull ErrorHandlingProperties properties) {
            this.properties = req(properties, "properties");
            return this;
        }

        @NonNull
        public Builder enricherProperties(@NonNull ErrorHandlingEnricherProperties enricherProperties) {
            this.enricherProperties = req(enricherProperties, "enricherProperties");
            return this;
        }

        @NonNull
        public Builder safetyNetProperties(@NonNull ErrorHandlingSafetyNetProperties safetyNetProperties) {
            this.safetyNetProperties = req(safetyNetProperties, "safetyNetProperties");
            return this;
        }

        @NonNull
        public Builder unknownHandler(@NonNull UnknownExceptionHandler unknownHandler) {
            this.unknownHandler = req(unknownHandler, "unknownHandler");
            return this;
        }

        @NonNull
        public Builder recorder(@NonNull ExceptionMetricRecorder recorder) {
            this.recorder = req(recorder, "recorder");
            return this;
        }

        @NonNull
        public Builder applicationName(@NonNull String applicationName) {
            this.applicationName = req(applicationName, "applicationName");
            return this;
        }

        @NonNull
        public Builder strategies(@NonNull List<TopExceptionHandlingStrategy> strategies) {
            this.strategies = req(strategies, "strategies");
            return this;
        }

        @NonNull
        public Builder entryEnrichers(@NonNull List<ErrorEntryEnricher> entryEnrichers) {
            this.entryEnrichers = req(entryEnrichers, "entryEnrichers");
            return this;
        }

        @NonNull
        public Builder stackHintExtractor(@NonNull StackHintExtractor stackHintExtractor) {
            this.stackHintExtractor = req(stackHintExtractor, "stackHintExtractor");
            return this;
        }

        @NonNull
        public Builder correlationIdResolver(@NonNull ReactiveCorrelationIdResolver correlationIdResolver) {
            this.correlationIdResolverOverride = req(correlationIdResolver, "correlationIdResolver");
            return this;
        }

        @NonNull
        public Builder tracingRequestContextSupplier(@Nullable Supplier<RequestContext> tracingRequestContextSupplier) {
            this.tracingRequestContextSupplier = tracingRequestContextSupplier;
            return this;
        }

        @NonNull
        public ReactiveGlobalExceptionHandler build() {
            return new ReactiveGlobalExceptionHandler(
                    config(),
                    dependencies(resolveCorrelationIdResolver()),
                    applicationName
            );
        }

        @NonNull
        private ReactiveExceptionHandlerConfig config() {
            return new ReactiveExceptionHandlerConfig(
                    properties,
                    enricherProperties,
                    safetyNetProperties
            );
        }

        @NonNull
        private ReactiveExceptionHandlerDependencies dependencies(@NonNull ReactiveCorrelationIdResolver resolver) {
            return new ReactiveExceptionHandlerDependencies(
                    List.copyOf(strategies),
                    unknownHandler,
                    recorder,
                    entryToDtoMapper,
                    jsonEncoder,
                    List.copyOf(entryEnrichers),
                    stackHintExtractor,
                    resolver,
                    tracingRequestContextSupplier
            );
        }

        @NonNull
        private ReactiveCorrelationIdResolver resolveCorrelationIdResolver() {
            return correlationIdResolverOverride != null
                    ? correlationIdResolverOverride
                    : ReactiveCorrelationIdResolver.from(properties);
        }
    }
}
