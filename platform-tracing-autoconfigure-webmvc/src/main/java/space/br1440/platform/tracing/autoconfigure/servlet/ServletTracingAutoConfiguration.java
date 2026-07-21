package space.br1440.platform.tracing.autoconfigure.servlet;

import jakarta.servlet.Servlet;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.client.RestClient;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.support.RequestIdentityBoundarySupport;

/**
 * Авто-конфигурация Servlet-уровня платформенного модуля трассировки.
 * <p>
 * Регистрирует {@link TraceResponseHeaderServletFilter} (добавление trace-заголовков в HTTP-ответ),
 * {@link PlatformServerRequestObservationConvention} (платформенные атрибуты для серверных
 * Observation'ов Spring MVC) и {@link PlatformClientRequestObservationConvention} (атрибуты для
 * исходящих {@code RestClient}/{@code RestTemplate}-запросов).
 * <p>
 * Активируется исключительно в Servlet-приложениях через
 * {@link ConditionalOnWebApplication.Type#SERVLET}. В реактивных приложениях используется
 * {@code ReactiveTracingAutoConfiguration}; параллельная активация исключена самим механизмом
 * Spring Boot.
 * <p>
 * Синхронизация MDC с активным trace-контекстом выполняется автоматически связкой
 * {@code micrometer-tracing-bridge-otel} + {@code micrometer-context-propagation}
 * (см. {@code platform-tracing-spring-boot-autoconfigure} build.gradle); отдельный
 * Servlet-фильтр для MDC не требуется и не регистрируется.
 */
@AutoConfiguration
@AutoConfigureAfter(TracingCoreAutoConfiguration.class)
@ConditionalOnClass({Servlet.class, FilterRegistrationBean.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = TracingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class ServletTracingAutoConfiguration {

    /**
     * Регистрация фильтра, добавляющего trace-заголовки в HTTP-ответ.
     * <p>
     * Активируется отдельным свойством {@code platform.tracing.response.expose-request-id-header},
     * чтобы клиенты могли отключить экспонирование заголовка независимо от прочих интеграций
     * платформы.
     */
    @Bean
    @ConditionalOnMissingBean(name = "platformTraceResponseHeaderServletFilterRegistration")
    @ConditionalOnProperty(prefix = TracingProperties.PREFIX + ".response", name = "expose-request-id-header",
            havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<TraceResponseHeaderServletFilter> platformTraceResponseHeaderServletFilterRegistration(
            TraceOperations traceOperations,
            TracingProperties properties,
            RequestIdentityBoundarySupport identityBoundary) {
        TraceResponseHeaderServletFilter filter =
                new TraceResponseHeaderServletFilter(traceOperations, properties, identityBoundary);
        FilterRegistrationBean<TraceResponseHeaderServletFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("platformTraceResponseHeaderServletFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
        return registration;
    }

    /**
     * Платформенная конвенция инструментации входящих HTTP-запросов в Servlet-стеке.
     * <p>
     * Активна только при наличии {@code DefaultServerRequestObservationConvention} (Spring Web,
     * Servlet-вариант) — в WebFlux-приложениях этот класс отсутствует, и регистрация не произойдёт.
     */
    @Bean
    @ConditionalOnClass(org.springframework.http.server.observation.DefaultServerRequestObservationConvention.class)
    @ConditionalOnMissingBean(PlatformServerRequestObservationConvention.class)
    public PlatformServerRequestObservationConvention platformServerRequestObservationConvention() {
        return new PlatformServerRequestObservationConvention();
    }

    /**
     * Платформенная конвенция инструментации исходящих HTTP-запросов в Servlet-стеке
     * ({@code RestClient} / {@code RestTemplate}).
     * <p>
     * Активна только при наличии Servlet-flavor
     * {@code DefaultClientRequestObservationConvention} (пакет
     * {@code org.springframework.http.client.observation}). Реактивный аналог
     * (для {@code WebClient}) реализован в WebFlux-модуле.
     */
    @Bean
    @ConditionalOnClass(org.springframework.http.client.observation.DefaultClientRequestObservationConvention.class)
    @ConditionalOnMissingBean(PlatformClientRequestObservationConvention.class)
    public PlatformClientRequestObservationConvention platformClientRequestObservationConvention() {
        return new PlatformClientRequestObservationConvention();
    }

    // --- Outbound propagation платформенных заголовков (Servlet client) ---
    // Активируется только при platform.tracing.propagation.outbound.enabled=true (secure-by-default).

    /**
     * Платформенный client-интерсептор для {@code RestTemplate}/{@code RestClient}.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = TracingProperties.PREFIX + ".propagation.outbound", name = "enabled", havingValue = "true")
    public PlatformOutboundHttpInterceptor platformOutboundHttpInterceptor(
            @Qualifier("platformHttpOutboundPolicy") OutboundPropagationPolicy policy,
            PlatformOutboundPropagation propagation) {
        return new PlatformOutboundHttpInterceptor(policy, propagation);
    }

    /**
     * Non-destructive привязка интерсептора ко всем {@code RestTemplate} (идемпотентно).
     */
    @Bean
    @ConditionalOnClass(RestTemplateCustomizer.class)
    @ConditionalOnProperty(prefix = TracingProperties.PREFIX + ".propagation.outbound", name = "enabled", havingValue = "true")
    public RestTemplateCustomizer platformOutboundRestTemplateCustomizer(PlatformOutboundHttpInterceptor interceptor) {
        return restTemplate -> {
            boolean alreadyPresent = restTemplate.getInterceptors().stream()
                    .anyMatch(i -> i instanceof PlatformOutboundHttpInterceptor);
            if (!alreadyPresent) {
                restTemplate.getInterceptors().add(interceptor);
            }
        };
    }

    /**
     * Non-destructive привязка интерсептора ко всем {@code RestClient.Builder}.
     */
    @Bean
    @ConditionalOnClass({RestClientCustomizer.class, RestClient.class})
    @ConditionalOnProperty(prefix = TracingProperties.PREFIX + ".propagation.outbound", name = "enabled", havingValue = "true")
    public RestClientCustomizer platformOutboundRestClientCustomizer(PlatformOutboundHttpInterceptor interceptor) {
        return builder -> builder.requestInterceptor(interceptor);
    }
}
