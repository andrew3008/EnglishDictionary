package space.br1440.platform.datasource.postgres.internal.integration.jooq;

import org.jooq.DSLContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.datasource.postgres.api.jooq.RoutingDSLContextFactory;
import space.br1440.platform.datasource.postgres.internal.proxy.core.RoutingDataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.jooq.DSLContext")
@ConditionalOnBean(RoutingDataSource.class)
public class RoutingJooqConfiguration {

    @Bean
    @ConditionalOnBean(DSLContext.class)
    @ConditionalOnMissingBean
    public RoutingDSLContextFactory routingDSLContextFactory(DSLContext dslContext) {
        return new RoutingDSLContextFactory(dslContext);
    }
}
