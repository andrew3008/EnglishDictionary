package space.br1440.platform.datasource.postgres.internal.proxy.configuration;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.datasource.postgres.internal.proxy.aop.RoutingAnnotationConflictValidator;
import space.br1440.platform.datasource.postgres.internal.proxy.lifecycle.DataSourceRoutingLifecycle;
import space.br1440.platform.datasource.postgres.internal.proxy.aop.NonTransactionalRoutingAspect;

@Configuration(proxyBeanMethods = false)
public class RoutingLifecycleConfiguration {

    @Bean
    @ConditionalOnBean(name = {"writeDataSource", "readDataSource"})
    @ConditionalOnMissingBean(DataSourceRoutingLifecycle.class)
    public DataSourceRoutingLifecycle dataSourceRoutingLifecycle(@Qualifier("writeDataSource") HikariDataSource writeDataSource,
                                                                 @Qualifier("readDataSource") HikariDataSource readDataSource) {
        return new DataSourceRoutingLifecycle(writeDataSource, readDataSource);
    }

    @Bean
    @ConditionalOnMissingBean(NonTransactionalRoutingAspect.class)
    public NonTransactionalRoutingAspect nonTransactionalRoutingAspect() {
        return new NonTransactionalRoutingAspect();
    }

    @Bean
    @ConditionalOnMissingBean(RoutingAnnotationConflictValidator.class)
    public RoutingAnnotationConflictValidator routingAnnotationConflictValidator(ListableBeanFactory beanFactory) {
        return new RoutingAnnotationConflictValidator(beanFactory);
    }
}
