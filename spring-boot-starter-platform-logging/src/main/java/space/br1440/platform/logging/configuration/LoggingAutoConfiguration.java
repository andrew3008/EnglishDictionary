package space.br1440.platform.logging.configuration;

import java.util.List;
import jakarta.servlet.Filter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.SecurityContextHolder;
import org.zalando.logbook.BodyFilter;
import org.zalando.logbook.HeaderFilter;
import org.zalando.logbook.HttpLogFormatter;
import org.zalando.logbook.HttpLogWriter;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.QueryFilter;
import org.zalando.logbook.RequestFilter;
import org.zalando.logbook.ResponseFilter;
import org.zalando.logbook.Sink;
import org.zalando.logbook.core.DefaultSink;
import org.zalando.logbook.servlet.LogbookFilter;

import space.br1440.platform.logging.SpringContext;
import space.br1440.platform.logging.UserService;
import space.br1440.platform.logging.aspect.MDCLoggingAspect;
import space.br1440.platform.logging.logbook.filter.BodySensitiveDataFilter;
import space.br1440.platform.logging.logbook.filter.HeaderSensitiveDataFilter;
import space.br1440.platform.logging.logbook.filter.HttpRequestFilter;
import space.br1440.platform.logging.logbook.filter.HttpResponseFilter;
import space.br1440.platform.logging.logbook.filter.QuerySensitiveDataFilter;
import space.br1440.platform.logging.logbook.formatter.ExcludedHeadersHttpLogFormatter;
import space.br1440.platform.logging.logbook.formatter.Rfc7230TruncatedBodyHttpLogFormatter;
import space.br1440.platform.logging.logbook.writer.PlatformHttpLogWriter;
import space.br1440.platform.logging.masking.MaskRules;
import space.br1440.platform.logging.masking.PlatformSensitiveDataMasker;
import space.br1440.platform.logging.masking.SensitiveDataMasker;
import space.br1440.platform.logging.masking.SensitiveDataRulesLoader;

@AutoConfiguration
@ConditionalOnProperty(value = "platform.http-logging.enable", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LoggingProperties.class)
public class LoggingAutoConfiguration {

    @Bean
    public RequestFilter platformHttpRequestFilter() {
        return new HttpRequestFilter();
    }

    @Bean
    public ResponseFilter platformHttpResponseFilter() {
        return new HttpResponseFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    public SensitiveDataMasker sensitiveDataMasker(LoggingProperties loggingProperties) {
        MaskRules maskRules = SensitiveDataRulesLoader.loadRules(loggingProperties);
        return new PlatformSensitiveDataMasker(maskRules);
    }

    @Bean
    @ConditionalOnMissingBean
    public MdcMaskerInitializer mdcMaskerInitializer(SensitiveDataMasker masker,
                                                     LoggingProperties loggingProperties) {
        return new MdcMaskerInitializer(masker, loggingProperties);
    }

    @Bean
    public HeaderFilter platformHeaderSensitiveDataFilter(SensitiveDataMasker sensitiveDataMasker) {
        return new HeaderSensitiveDataFilter(sensitiveDataMasker);
    }

    @Bean
    public QueryFilter platformQuerySensitiveDataFilter(SensitiveDataMasker sensitiveDataMasker) {
        return new QuerySensitiveDataFilter(sensitiveDataMasker);
    }

    @Bean
    public BodyFilter platformBodySensitiveDataFilter(SensitiveDataMasker sensitiveDataMasker) {
        return new BodySensitiveDataFilter(sensitiveDataMasker);
    }

    @Bean
    @ConditionalOnMissingBean
    public HttpLogFormatter logFormatter(LoggingProperties properties) {
        HttpLogFormatter rfc7230Formatter = new Rfc7230TruncatedBodyHttpLogFormatter(properties);
        return new ExcludedHeadersHttpLogFormatter(properties, rfc7230Formatter);
    }

    @Bean
    @ConditionalOnMissingBean
    public HttpLogWriter httpLogWriter() {
        return new PlatformHttpLogWriter();
    }

    @Bean
    @ConditionalOnMissingBean
    public Sink consoleSink(HttpLogFormatter logFormatter, HttpLogWriter logWriter) {
        return new DefaultSink(logFormatter, logWriter);
    }

    @Bean
    public Logbook logbook(List<RequestFilter> requestFilters,
                           List<ResponseFilter> responseFilters,
                           List<HeaderFilter> headerFilters,
                           List<QueryFilter> queryFilters,
                           List<BodyFilter> bodyFilters,
                           Sink consoleSink) {
        return Logbook.builder()
                .requestFilters(requestFilters)
                .responseFilters(responseFilters)
                .headerFilters(headerFilters)
                .queryFilters(queryFilters)
                .bodyFilters(bodyFilters)
                .sink(consoleSink)
                .build();
    }

    @Bean
    @ConditionalOnProperty(value = "platform.http-logging.servlet-filter.enabled", havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<Filter> platformLogbookFilterRegistration(Logbook logbook) {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new LogbookFilter(logbook));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public MDCLoggingAspect mdcLoggingAspect() {
        return new MDCLoggingAspect();
    }

    @Bean
    public SpringContext springContext() {
        return new SpringContext();
    }

    @Bean
    @ConditionalOnClass(SecurityContextHolder.class)
    public UserService userService() {
        return new UserService();
    }
}
