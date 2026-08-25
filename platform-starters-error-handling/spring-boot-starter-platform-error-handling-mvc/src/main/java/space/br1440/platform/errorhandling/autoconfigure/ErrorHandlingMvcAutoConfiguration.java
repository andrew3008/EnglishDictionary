package space.br1440.platform.errorhandling.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import space.br1440.platform.errorhandling.core.advice.GlobalExceptionHandler;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.strategy.top.SpringMvcExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.CorrelationIdResolver;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorEntryEnricher;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.mvc.internal.CorrelationIdFilter;
import space.br1440.platform.errorhandling.mvc.internal.ExceptionLogger;
import space.br1440.platform.errorhandling.mvc.internal.MdcAwareTaskDecorator;
import space.br1440.platform.errorhandling.mvc.internal.ProblemResponseFactory;
import space.br1440.platform.errorhandling.mvc.observability.ExceptionObservabilityHook;
import space.br1440.platform.errorhandling.mvc.observability.MicrometerExceptionMetricsRecorder;
import space.br1440.platform.errorhandling.mvc.observability.OtelExceptionRecorder;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.model.RequestContext;
import space.br1440.platform.errorhandling.model.mapping.ErrorEntryToDtoMapper;
import java.util.List;
import java.util.function.Supplier;

@AutoConfiguration(after = ErrorHandlingCoreAutoConfiguration.class)
@AutoConfigureBefore(ErrorMvcAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = ErrorHandlingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
@ImportRuntimeHints(ErrorHandlingMvcRuntimeHints.class)
public class ErrorHandlingMvcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ProblemResponseFactory problemResponseFactory(ErrorEntryToDtoMapper mapper,
                                                         Environment environment,
                                                         ErrorHandlingProperties properties) {
        String applicationName = environment.getProperty("spring.application.name", "unknown");
        return new ProblemResponseFactory(mapper, applicationName, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExceptionLogger exceptionLogger(ErrorHandlingProperties properties) {
        return new ExceptionLogger(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public CorrelationIdResolver correlationIdResolver(ErrorHandlingProperties properties) {
        return new CorrelationIdResolver(
                properties.getCorrelationId(),
                properties.getTracing().isAcceptTraceparent()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler(ErrorHandlingProperties properties,
                                                         @Qualifier("orderedTopExceptionHandlingStrategies")
                                                         List<TopExceptionHandlingStrategy> strategies,
                                                         UnknownExceptionHandler unknownExceptionHandler,
                                                         ExceptionMetricRecorder exceptionMetricRecorder,
                                                         ProblemResponseFactory responseFactory,
                                                         ExceptionLogger exceptionLogger,
                                                         CorrelationIdResolver correlationIdResolver,
                                                         @Qualifier("orderedErrorEntryEnrichers")
                                                         List<ErrorEntryEnricher> entryEnrichers,
                                                         StackHintExtractor stackHintExtractor,
                                                         List<ExceptionObservabilityHook> observabilityHooks,
                                                         @Qualifier("tracingRequestContextSupplier")
                                                         ObjectProvider<Supplier<RequestContext>> tracingRequestContextSupplierProvider) {
        return new GlobalExceptionHandler(
                properties,
                strategies,
                unknownExceptionHandler,
                exceptionMetricRecorder,
                responseFactory,
                exceptionLogger,
                correlationIdResolver,
                entryEnrichers,
                stackHintExtractor,
                observabilityHooks,
                tracingRequestContextSupplierProvider.getIfAvailable()
        );
    }

    @Bean
    @ConditionalOnClass(name = "io.opentelemetry.api.trace.Span")
    @ConditionalOnMissingBean(OtelExceptionRecorder.class)
    public ExceptionObservabilityHook otelExceptionRecorder() {
        return new OtelExceptionRecorder();
    }

    @Bean
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnBean(io.micrometer.core.instrument.MeterRegistry.class)
    @ConditionalOnMissingBean(MicrometerExceptionMetricsRecorder.class)
    public ExceptionObservabilityHook micrometerExceptionMetricsRecorder(MeterRegistry registry) {
        return new MicrometerExceptionMetricsRecorder(registry);
    }

    @Bean
    @ConditionalOnMissingBean(SpringMvcExceptionStrategy.class)
    public SpringMvcExceptionStrategy springMvcExceptionStrategy(CauseChainResolver causeChainResolver) {
        return new SpringMvcExceptionStrategy(causeChainResolver);
    }

    @Bean
    @ConditionalOnMissingBean(CorrelationIdFilter.class)
    public CorrelationIdFilter correlationIdFilter(CorrelationIdResolver resolver,
                                                    @Qualifier("tracingRequestContextSupplier")
                                                    ObjectProvider<Supplier<RequestContext>> tracingRequestContextSupplierProvider) {
        return new CorrelationIdFilter(resolver, tracingRequestContextSupplierProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(name = "correlationIdFilterRegistration")
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration(CorrelationIdFilter filter) {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 60);
        registration.addUrlPatterns("/*");
        registration.setName("correlationIdFilter");
        return registration;
    }

    /**
     * Bean-декоратор для копирования MDC в дочерние virtual/platform threads.
     *
     * <p>В Java 21 + {@code spring.threads.virtual.enabled=true} без этого декоратора
     * MDC пропадает на каждой async-границе.
     */
    @Bean
    @ConditionalOnMissingBean(MdcAwareTaskDecorator.class)
    public MdcAwareTaskDecorator mdcAwareTaskDecorator() {
        return new MdcAwareTaskDecorator();
    }
}
