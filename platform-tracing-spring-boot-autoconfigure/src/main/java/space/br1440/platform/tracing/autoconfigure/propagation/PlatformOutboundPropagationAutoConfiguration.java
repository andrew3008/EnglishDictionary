package space.br1440.platform.tracing.autoconfigure.propagation;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundInjector;
import space.br1440.platform.tracing.api.propagation.control.TrustedDestinationMatcher;
import space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;

/**
 * Общие (framework-agnostic) бины outbound-пропагации платформенных заголовков.
 * <p>
 * Эти бины используются client-интерсепторами Servlet ({@code platform-tracing-autoconfigure-webmvc}),
 * WebFlux ({@code platform-tracing-autoconfigure-webflux}) и Kafka-производителем. Сами типы живут в
 * {@code platform-tracing-api} (видны и app-, и agent-classloader — см. ADR-outbound-propagation).
 *
 * <p>Бины создаются всегда (с {@code @ConditionalOnMissingBean}); фактическая инжекция гейтится на уровне
 * политики ({@link OutboundPropagationPolicy#decide(String)} -> {@code DENY_ALL}, если outbound выключен
 * или destination недоверенный) и на уровне регистрации интерсепторов в web-модулях.
 */
@AutoConfiguration
@AutoConfigureAfter(TracingCoreAutoConfiguration.class)
@ConditionalOnProperty(prefix = TracingProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class PlatformOutboundPropagationAutoConfiguration {

    /**
     * Единый инжектор платформенных заголовков (имена берутся из {@code platform.tracing.propagation.platform-headers}).
     */
    @Bean
    @ConditionalOnMissingBean
    public PlatformOutboundInjector platformOutboundInjector(TracingProperties properties) {
        TracingProperties.Propagation.PlatformHeadersConfig headers =
                properties.getPropagation().getPlatformHeaders();
        return new PlatformOutboundInjector(
                headers.getForceTraceHeader(),
                headers.getQaTraceHeader(),
                headers.getRequestIdHeader());
    }

    /**
     * Политика исходящей передачи на HTTP-хосты (trusted-host gating + per-header флаги).
     */
    @Bean(name = "platformHttpOutboundPolicy")
    @ConditionalOnMissingBean(name = "platformHttpOutboundPolicy")
    public OutboundPropagationPolicy platformHttpOutboundPolicy(TracingProperties properties) {
        TracingProperties.Propagation.Outbound outbound = properties.getPropagation().getOutbound();
        TrustedDestinationMatcher matcher = TrustedDestinationMatcher.forHttpHosts(
                outbound.getTrustedHostPatterns(), outbound.isAllowIpLiterals());
        return new OutboundPropagationPolicy(
                outbound.isEnabled(),
                matcher,
                outbound.isPropagateForceTrace(),
                outbound.isPropagateQaTrace(),
                outbound.isPropagateRequestId());
    }
}
