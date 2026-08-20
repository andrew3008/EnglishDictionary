package space.br1440.platform.errorhandling.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.reactive.error.ErrorWebFluxAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import space.br1440.platform.errorhandling.core.strategy.cause.WebFluxCauseExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.top.WebFluxExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.top.WebFluxFrameworkExceptionStrategy;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.util.ReactiveCorrelationIdResolver;
import space.br1440.platform.errorhandling.extension.ErrorEntryEnricher;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.core.handler.ReactiveExceptionHandlerDependencies;
import space.br1440.platform.errorhandling.core.handler.ReactiveGlobalExceptionHandler;
import space.br1440.platform.errorhandling.core.handler.ReactiveExceptionHandlerConfig;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.model.RequestContext;
import space.br1440.platform.errorhandling.model.mapping.ErrorEntryToDtoMapper;
import space.br1440.platform.errorhandling.webflux.properties.ErrorHandlingEnricherProperties;
import space.br1440.platform.errorhandling.webflux.properties.ErrorHandlingSafetyNetProperties;
import space.br1440.platform.errorhandling.webflux.properties.ErrorHandlingUpstreamProperties;

import java.util.List;
import java.util.function.Supplier;

@AutoConfiguration(
        after = ErrorHandlingCoreAutoConfiguration.class,
        before = ErrorWebFluxAutoConfiguration.class
)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@EnableConfigurationProperties({
        ErrorHandlingProperties.class,
        ErrorHandlingUpstreamProperties.class,
        ErrorHandlingEnricherProperties.class,
        ErrorHandlingSafetyNetProperties.class
})
@ConditionalOnProperty(prefix = ErrorHandlingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class ErrorHandlingWebFluxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ReactiveCorrelationIdResolver reactiveCorrelationIdResolver(ErrorHandlingProperties properties) {
        return ReactiveCorrelationIdResolver.from(properties);
    }

    @Bean(name = "errorHandlingJsonEncoder")
    public Jackson2JsonEncoder errorHandlingJsonEncoder(ObjectMapper objectMapper) {
        return new Jackson2JsonEncoder(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactiveExceptionHandlerConfig reactiveExceptionHandlerConfig(ErrorHandlingProperties properties,
                                                                         ErrorHandlingEnricherProperties enricherProperties,
                                                                         ErrorHandlingSafetyNetProperties safetyNetProperties) {
        return new ReactiveExceptionHandlerConfig(properties, enricherProperties, safetyNetProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactiveExceptionHandlerDependencies reactiveExceptionHandlerDependencies(@Qualifier("orderedTopExceptionHandlingStrategies") List<TopExceptionHandlingStrategy> strategies,
                                                                                     UnknownExceptionHandler unknownExceptionHandler,
                                                                                     ExceptionMetricRecorder exceptionMetricRecorder,
                                                                                     ErrorEntryToDtoMapper errorEntryToDtoMapper,
                                                                                     @Qualifier("errorHandlingJsonEncoder") Jackson2JsonEncoder jsonEncoder,
                                                                                     @Qualifier("orderedErrorEntryEnrichers") List<ErrorEntryEnricher> entryEnrichers,
                                                                                     StackHintExtractor stackHintExtractor,
                                                                                     ReactiveCorrelationIdResolver correlationIdResolver,
                                                                                     @Qualifier("tracingRequestContextSupplier")
                                                                                     ObjectProvider<Supplier<RequestContext>> tracingRequestContextSupplierProvider) {
        return new ReactiveExceptionHandlerDependencies(
                strategies,
                unknownExceptionHandler,
                exceptionMetricRecorder,
                errorEntryToDtoMapper,
                jsonEncoder,
                entryEnrichers,
                stackHintExtractor,
                correlationIdResolver,
                tracingRequestContextSupplierProvider.getIfAvailable()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactiveGlobalExceptionHandler reactiveGlobalExceptionHandler(ReactiveExceptionHandlerConfig reactiveExceptionHandlerConfig,
                                                                         ReactiveExceptionHandlerDependencies reactiveExceptionHandlerDependencies,
                                                                         Environment environment) {
        String applicationName = environment.getProperty("spring.application.name", "unknown");
        return new ReactiveGlobalExceptionHandler(reactiveExceptionHandlerConfig, reactiveExceptionHandlerDependencies, applicationName);
    }

    @Bean
    @ConditionalOnMissingBean(name = "webFluxFrameworkExceptionStrategy")
    public TopExceptionHandlingStrategy webFluxFrameworkExceptionStrategy(CauseChainResolver causeChainResolver) {
        return new WebFluxFrameworkExceptionStrategy(causeChainResolver);
    }

    @Bean
    @ConditionalOnMissingBean(name = "webFluxTopExceptionStrategy")
    public TopExceptionHandlingStrategy webFluxTopExceptionStrategy(CauseChainResolver causeChainResolver) {
        return new WebFluxExceptionStrategy(causeChainResolver);
    }

    @Bean
    @ConditionalOnMissingBean(name = "webFluxRootCauseExceptionStrategy")
    public RootCauseExceptionHandlingStrategy webFluxRootCauseExceptionStrategy(StackHintExtractor stackHintExtractor,
                                                                                ErrorHandlingUpstreamProperties upstreamProperties) {
        return new WebFluxCauseExceptionStrategy(stackHintExtractor, upstreamProperties);
    }

    @Bean
    @ConditionalOnClass(name = "io.micrometer.context.ContextRegistry")
    @ConditionalOnMissingBean
    public static ReactorContextPropagationInitializer reactorContextPropagationInitializer() {
        return new ReactorContextPropagationInitializer();
    }
}
