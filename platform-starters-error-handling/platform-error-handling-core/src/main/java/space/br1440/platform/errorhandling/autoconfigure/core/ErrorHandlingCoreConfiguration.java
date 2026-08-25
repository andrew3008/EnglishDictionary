package space.br1440.platform.errorhandling.autoconfigure.core;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.errorhandling.core.enricher.HelpDetailEnricher;
import space.br1440.platform.errorhandling.core.enricher.UpstreamIdentityEnricher;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.resolver.ResponseStatusResolverBean;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorEntryEnricher;
import space.br1440.platform.errorhandling.extension.ResponseStatusContributor;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.http.DefaultHttpStatusResolver;
import space.br1440.platform.errorhandling.http.HttpStatusResolver;
import space.br1440.platform.errorhandling.model.ErrorEntryFactory;
import space.br1440.platform.errorhandling.model.mapping.ErrorEntryToDtoMapper;
import space.br1440.platform.errorhandling.model.error_detail.dto.ErrorInfoDetailDTO;

import java.util.List;
import java.util.function.Supplier;

@Configuration(proxyBeanMethods = false)
public class ErrorHandlingCoreConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ErrorEntryToDtoMapper errorEntryToDtoMapper() {
        return new ErrorEntryToDtoMapper();
    }

    @Bean
    @ConditionalOnMissingBean(HttpStatusResolver.class)
    public HttpStatusResolver httpStatusResolver() {
        return new DefaultHttpStatusResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public ResponseStatusResolverBean responseStatusResolverBean(List<ResponseStatusContributor> contributors) {
        return new ResponseStatusResolverBean(contributors);
    }

    @Bean
    @ConditionalOnMissingBean
    public CauseChainResolver causeChainResolver(List<RootCauseExceptionHandlingStrategy> rootCauseAwareStrategies) {
        return new CauseChainResolver(rootCauseAwareStrategies);
    }

    @Bean
    @ConditionalOnMissingBean
    public HelpDetailEnricher helpDetailEnricher(ErrorHandlingProperties properties) {
        return new HelpDetailEnricher(properties.getErrorCatalogBaseUrl());
    }

    @Bean(name = "orderedTopExceptionHandlingStrategies")
    public List<TopExceptionHandlingStrategy> orderedTopExceptionHandlingStrategies(List<TopExceptionHandlingStrategy> strategies) {
        return strategies.stream()
                .sorted(AnnotationAwareOrderComparator.INSTANCE)
                .toList();
    }

    @Bean(name = "orderedErrorEntryEnrichers")
    public List<ErrorEntryEnricher> orderedErrorEntryEnrichers(List<ErrorEntryEnricher> enrichers) {
        return enrichers.stream()
                .sorted(AnnotationAwareOrderComparator.INSTANCE)
                .toList();
    }

    @Bean
    @ConditionalOnMissingBean
    public StackHintExtractor stackHintExtractor(ErrorHandlingProperties properties) {
        return new StackHintExtractor(
                Boolean.TRUE.equals(properties.getIncludeStackHint()),
                List.of()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ErrorEntryFactory errorEntryFactory(ObjectProvider<Supplier<String>> localServiceNameProvider) {
        Supplier<String> supplier = localServiceNameProvider.getIfAvailable(() -> () -> ErrorInfoDetailDTO.DOMAIN_UNSPECIFIED);
        String localDomain = supplier.get();
        if (StringUtils.isNullOrBlank(localDomain)) {
            localDomain = ErrorInfoDetailDTO.DOMAIN_UNSPECIFIED;
        }
        return new ErrorEntryFactory(localDomain);
    }

    @Bean
    @ConditionalOnMissingBean
    public UpstreamIdentityEnricher upstreamIdentityEnricher() {
        return new UpstreamIdentityEnricher();
    }
}
