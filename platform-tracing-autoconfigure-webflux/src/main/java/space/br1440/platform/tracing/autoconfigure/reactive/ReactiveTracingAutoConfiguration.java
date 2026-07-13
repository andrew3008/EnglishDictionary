package space.br1440.platform.tracing.autoconfigure.reactive;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebFilter;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.TraceControlHeaderInjector;
import space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;

import java.util.Optional;

import jakarta.annotation.PostConstruct;
import io.opentelemetry.api.trace.Span;
import space.br1440.platform.tracing.api.mdc.RemoteServiceContextReaders;
import space.br1440.platform.tracing.api.mdc.RemoteServiceTraceMirror;

/**
 * Авто-конфигурация реактивного веб-уровня (WebFlux) платформенного модуля трассировки.
 * <p>
 * Регистрирует {@link TraceResponseHeaderWebFilter} (добавление trace-заголовков в HTTP-ответ),
 * {@link PlatformReactiveServerRequestObservationConvention} (платформенные атрибуты для
 * серверных Observation'ов WebFlux) и {@link PlatformReactiveClientRequestObservationConvention}
 * (атрибуты для исходящих запросов через {@code WebClient}).
 * <p>
 * Активируется исключительно в реактивных приложениях через
 * {@link ConditionalOnWebApplication.Type#REACTIVE}. В Servlet-приложениях используется
 * {@code ServletTracingAutoConfiguration}; параллельная активация исключена самим механизмом
 * Spring Boot.
 * <p>
 * Синхронизация MDC с активным trace-контекстом выполняется автоматически связкой
 * {@code micrometer-tracing-bridge-otel} + {@code micrometer-context-propagation} через
 * стандартные {@code ThreadLocalAccessor}'ы Micrometer Tracing; отдельный реактивный
 * {@code WebFilter} для MDC не регистрируется. Для корректной работы при включённом
 * {@code spring.main.lazy-initialization=true} см. {@link TracingReactorEagerInitConfiguration}.
 */
@AutoConfiguration
@AutoConfigureAfter(TracingCoreAutoConfiguration.class)
@ConditionalOnClass({WebFluxConfigurer.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = TracingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReactiveTracingAutoConfiguration {

    /**
     * Регистрация реактивного {@link WebFilter}, добавляющего trace-заголовки в HTTP-ответ.
     * <p>
     * Порядок выставлен близко к началу реактивной цепочки фильтров, чтобы заголовок
     * гарантированно попадал в ответ независимо от исхода обработки запроса.
     */
    @Bean
    @ConditionalOnMissingBean(TraceResponseHeaderWebFilter.class)
    @ConditionalOnProperty(prefix = TracingProperties.PREFIX + ".response", name = "expose-request-id-header",
            havingValue = "true", matchIfMissing = true)
    @Order(Ordered.HIGHEST_PRECEDENCE + 50)
    public TraceResponseHeaderWebFilter platformTraceResponseHeaderWebFilter(TraceOperations traceOperations,
                                                                             TracingProperties properties) {
        return new TraceResponseHeaderWebFilter(traceOperations, properties);
    }

    /**
     * Платформенная конвенция инструментации входящих HTTP-запросов в реактивном стеке.
     * <p>
     * Активна только при наличии реактивного {@code DefaultServerRequestObservationConvention}
     * (Spring WebFlux); в Servlet-приложениях этот класс отсутствует, и регистрация не произойдёт.
     */
    @Bean
    @org.springframework.context.annotation.Primary
    @ConditionalOnClass(org.springframework.http.server.reactive.observation.DefaultServerRequestObservationConvention.class)
    @ConditionalOnMissingBean(PlatformReactiveServerRequestObservationConvention.class)
    public PlatformReactiveServerRequestObservationConvention platformReactiveServerRequestObservationConvention() {
        return new PlatformReactiveServerRequestObservationConvention();
    }

    /**
     * Платформенная конвенция инструментации исходящих HTTP-запросов через {@code WebClient}.
     * <p>
     * Активна только при наличии реактивного
     * {@code org.springframework.web.reactive.function.client.DefaultClientRequestObservationConvention}
     * — это родитель из артефакта {@code spring-webflux}, не Servlet-flavor из {@code spring-web}.
     * В Servlet-приложениях используется отдельный {@code PlatformClientRequestObservationConvention}
     * (модуль {@code platform-tracing-autoconfigure-webmvc}).
     */
    @Bean
    @ConditionalOnClass(org.springframework.web.reactive.function.client.DefaultClientRequestObservationConvention.class)
    @ConditionalOnMissingBean(PlatformReactiveClientRequestObservationConvention.class)
    public PlatformReactiveClientRequestObservationConvention platformReactiveClientRequestObservationConvention() {
        return new PlatformReactiveClientRequestObservationConvention();
    }

    /**
     * Регистрирует Micrometer {@code ThreadLocalAccessor} для {@code platform.remote.service}
     * и дополнительные context readers для WebFlux error-handling.
     */
    @Bean
    @ConditionalOnMissingBean(RemoteServiceWebFluxMirrorConfigurer.class)
    public RemoteServiceWebFluxMirrorConfigurer remoteServiceWebFluxMirrorConfigurer() {
        return new RemoteServiceWebFluxMirrorConfigurer();
    }

    // --- Outbound propagation платформенных заголовков (WebClient) ---
    // Активируется только при platform.tracing.propagation.outbound.enabled=true (secure-by-default).

    /**
     * Платформенный {@code ExchangeFilterFunction} для {@code WebClient}.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = TracingProperties.PREFIX + ".propagation.outbound", name = "enabled", havingValue = "true")
    public PlatformOutboundExchangeFilterFunction platformOutboundExchangeFilterFunction(
            @Qualifier("platformHttpOutboundPolicy") OutboundPropagationPolicy policy,
            TraceControlHeaderInjector injector) {
        return new PlatformOutboundExchangeFilterFunction(policy, injector);
    }

    /**
     * Non-destructive привязка фильтра ко всем {@code WebClient.Builder} (идемпотентно).
     * <p>
     * Ограничение: применяется только к {@code WebClient}, построенным из инжектируемого/авто-конфигурированного
     * {@code WebClient.Builder}. Клиенты, созданные вручную ({@code WebClient.create()}), не получают ни W3C-трейсинг
     * Агента, ни платформенные заголовки. См. {@code docs/SUPPORTED.md} (раздел Outbound propagation).
     */
    @Bean
    @ConditionalOnClass({WebClientCustomizer.class, WebClient.class})
    @ConditionalOnProperty(prefix = TracingProperties.PREFIX + ".propagation.outbound", name = "enabled", havingValue = "true")
    public WebClientCustomizer platformOutboundWebClientCustomizer(PlatformOutboundExchangeFilterFunction filter) {
        return builder -> builder.filters(filters -> {
            boolean alreadyPresent = filters.stream()
                    .anyMatch(f -> f instanceof PlatformOutboundExchangeFilterFunction);
            if (!alreadyPresent) {
                filters.add(filter);
            }
        });
    }

    /**
     * Инициализатор Reactor/MDC mirror для {@code platform.remote.service}.
     */
    public static final class RemoteServiceWebFluxMirrorConfigurer {

        @PostConstruct
        void init() {
            RemoteServiceContextPropagation.registerIfAbsent();
            RemoteServiceContextReaders.register(() -> {
                try {
                    Span span = Span.current();
                    if (span == null || !span.getSpanContext().isValid()) {
                        return Optional.empty();
                    }
                    return RemoteServiceTraceMirror.get(span.getSpanContext().getTraceId());
                } catch (RuntimeException ignored) {
                    return Optional.empty();
                }
            });
        }
    }
}
