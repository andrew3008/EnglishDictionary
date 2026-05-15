package space.br1440.platform.datasource.config.autoconfigure;

import org.jooq.DSLContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.datasource.jooq.RoutingDSLContextFactory;
import space.br1440.platform.datasource.postgres.RoutingDataSource;

/**
 * Регистрирует {@link RoutingDSLContextFactory} для jOOQ read/write API, если jOOQ присутствует
 * на classpath и {@link RoutingDataSource} уже зарегистрирован.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.jooq.DSLContext")
@ConditionalOnBean(RoutingDataSource.class)
public class JooqRoutingConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RoutingDSLContextFactory routingDSLContextFactory(DSLContext dslContext) {
        return new RoutingDSLContextFactory(dslContext);
    }
}
