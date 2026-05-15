package space.br1440.platform.errorhandling.autoconfigure.core;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.resolver.ResponseStatusResolverBean;
import space.br1440.platform.errorhandling.core.strategy.cause.core.ErrorEntryCauseStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.http.AnnotationResponseStatusContributor;
import space.br1440.platform.errorhandling.core.strategy.cause.http.SpringResponseStatusExceptionContributor;
import space.br1440.platform.errorhandling.core.strategy.top.*;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.http.HttpStatusResolver;

@Configuration(proxyBeanMethods = false)
public class ErrorHandlingTopStrategiesConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "businessExceptionStrategy")
    public TopExceptionHandlingStrategy businessExceptionStrategy(CauseChainResolver causeChainResolver,
                                                                  HttpStatusResolver httpStatusResolver) {
        return new BusinessExceptionStrategy(causeChainResolver, httpStatusResolver);
    }

    @Bean
    @ConditionalOnMissingBean(name = "validationExceptionStrategy")
    public TopExceptionHandlingStrategy validationExceptionStrategy(CauseChainResolver causeChainResolver) {
        return new ValidationExceptionStrategy(causeChainResolver);
    }

    @Bean
    @ConditionalOnMissingBean(AnnotationResponseStatusContributor.class)
    public AnnotationResponseStatusContributor annotationResponseStatusContributor() {
        return new AnnotationResponseStatusContributor();
    }

    @Bean
    @ConditionalOnMissingBean(SpringResponseStatusExceptionContributor.class)
    public SpringResponseStatusExceptionContributor springResponseStatusExceptionContributor() {
        return new SpringResponseStatusExceptionContributor();
    }

    @Bean
    @ConditionalOnMissingBean(name = "responseStatusExceptionStrategy")
    public TopExceptionHandlingStrategy responseStatusExceptionStrategy(ResponseStatusResolverBean responseStatusResolverBean,
                                                                        CauseChainResolver causeChainResolver) {
        return new ResponseStatusExceptionStrategy(responseStatusResolverBean, causeChainResolver);
    }

    @Bean
    @ConditionalOnMissingBean(UnknownExceptionHandler.class)
    public UnknownExceptionHandler unknownExceptionHandler(CauseChainResolver causeChainResolver) {
        return new DefaultUnknownExceptionHandler(causeChainResolver);
    }

    @Bean
    @ConditionalOnMissingBean(name = "nestedBusinessExceptionStrategy")
    public RootCauseExceptionHandlingStrategy nestedBusinessExceptionStrategy(HttpStatusResolver httpStatusResolver,
                                                                              StackHintExtractor stackHintExtractor,
                                                                              Environment environment) {
        String applicationName = environment.getProperty("spring.application.name", "unknown");
        return new ErrorEntryCauseStrategy(httpStatusResolver, stackHintExtractor, applicationName);
    }
}
