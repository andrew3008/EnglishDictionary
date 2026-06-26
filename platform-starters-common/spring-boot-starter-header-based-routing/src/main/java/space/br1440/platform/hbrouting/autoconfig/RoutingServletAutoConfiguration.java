package space.br1440.platform.hbrouting.autoconfig;

import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import space.br1440.platform.hbrouting.config.RoutingProperties;
import space.br1440.platform.hbrouting.http.RoutingHeaderFilter;
import space.br1440.platform.hbrouting.support.RoutingHeaderValidator;

@AutoConfiguration(after = RoutingAutoConfiguration.class)
@ConditionalOnClass(Filter.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RoutingServletAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RoutingHeaderFilter routingHeaderFilter(
            RoutingProperties properties,
            RoutingHeaderValidator validator
    ) {
        return new RoutingHeaderFilter(properties, validator);
    }

    @Bean
    @ConditionalOnMissingBean(name = "routingHeaderFilterRegistrationBean")
    public FilterRegistrationBean<RoutingHeaderFilter> routingHeaderFilterRegistrationBean(
            RoutingHeaderFilter filter
    ) {
        FilterRegistrationBean<RoutingHeaderFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
